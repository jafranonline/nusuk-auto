package com.nusuk.automator;

import android.content.Context;
import android.util.AtomicFile;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class KeyRepository {
    private static final String TAG = "KeyRepo";
    private static final String STATE_FILE = "keys_state.json";
    /**
     * Tried before anything else, once per phone (a tested code is never tried again). The server
     * can add more: /api/app/progress returns the keys marked priority in the web DB.
     */
    private static final String[] PRIORITY_CODES = {"NG-WO4DFqSh", "NG-qlVHTrFa"};
    private static KeyRepository instance;

    public interface ChangeListener {
        void onKeysChanged();
    }

    private final Context context;
    private final List<String> allCodes = new ArrayList<>();
    private final Set<String> successCodes = new LinkedHashSet<>();
    private final Set<String> invalidCodes = new LinkedHashSet<>();
    // Nusuk said "already used": this account added the code before (another phone, by hand, an
    // older install). Never tried again, but not counted as added by this app.
    private final Set<String> alreadyCodes = new LinkedHashSet<>();
    // Shared list from the server: codes that failed on several phones and never worked anywhere.
    private final Set<String> deadCodes = new HashSet<>();
    private long deadSince = 0;       // server cursor for dead-list changes
    private final List<ChangeListener> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();
    private int currentIndex = 0;     // legacy (codes were once tried in order); kept in saved state
    private final Random picker = new Random();
    private Set<String> codeKeys = null; // upper-case copy of allCodes, for de-duplicating downloads
    private long codesCursor = 0;    // server key id the downloaded list is complete up to
    private int codesVersion = 0;     // CODES_LIST_VERSION of the saved list (1 = before versioning)
    private int remainingCache = -1;  // untested codes in the current list; -1 = recount
    // Codes that got no answer this session; not persisted, so they are tried again later.
    private final Set<String> deferredCodes = new HashSet<>();
    // Priority keys last received from the server (persisted), tried before the main list.
    private final List<String> serverPriority = new ArrayList<>();
    // Results not yet backed up to the server (persisted, so a kill never loses them).
    private final Set<String> pendingSuccess = new LinkedHashSet<>();
    private final Set<String> pendingInvalid = new LinkedHashSet<>();
    private final Set<String> pendingAlready = new LinkedHashSet<>();
    // false until this install has pulled its history from the server (after reinstall) and
    // pushed everything it already had (first run of a version with backup).
    private boolean restored = false;
    // Priority codes already given their one re-check after being marked invalid (persisted).
    private final Set<String> priorityRechecked = new HashSet<>();

    // The saved state (about 20k codes) is parsed on a background thread so the first screen
    // doesn't wait for it. The loader holds this object's lock while it works, so every
    // synchronized method (the automation's) simply waits for it; the UI-facing counters below
    // return 0 instead of waiting, and listeners are notified when loading is done.
    private volatile boolean loaded = false;
    private final List<Runnable> whenLoaded = new ArrayList<>();

    private KeyRepository(Context context) {
        this.context = context.getApplicationContext();
        final java.util.concurrent.CountDownLatch lockTaken = new java.util.concurrent.CountDownLatch(1);
        new Thread(() -> {
            List<Runnable> run;
            synchronized (this) {
                lockTaken.countDown();
                loadState();
            }
            synchronized (whenLoaded) {
                loaded = true;
                run = new ArrayList<>(whenLoaded);
                whenLoaded.clear();
            }
            for (Runnable r : run) {
                try { r.run(); } catch (Exception e) { Log.e(TAG, "whenLoaded", e); }
            }
            notifyListeners();
            CodeSync.syncSoon(this.context, 0);
        }, "keys-load").start();
        try { lockTaken.await(); } catch (InterruptedException ignored) {}
    }

    public boolean isLoaded() { return loaded; }

    /** Runs `r` now if the saved state is loaded, else right after it is (on the loader thread). */
    public void whenLoaded(Runnable r) {
        synchronized (whenLoaded) {
            if (!loaded) { whenLoaded.add(r); return; }
        }
        r.run();
    }

    public static synchronized KeyRepository getInstance(Context context) {
        if (instance == null) {
            instance = new KeyRepository(context);
        }
        return instance;
    }

    public void registerListener(ChangeListener listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void unregisterListener(ChangeListener listener) {
        listeners.remove(listener);
    }

    /** Lets other counters (quiz stats) refresh the same listeners. */
    public void notifyChanged() {
        notifyListeners();
    }

    private void notifyListeners() {
        for (ChangeListener l : new ArrayList<>(listeners)) {
            try {
                l.onKeysChanged();
            } catch (Exception e) {
                Log.e(TAG, "Listener error", e);
            }
        }
    }

    public int getTotalCount() {
        if (!loaded) return 0; // never block the UI thread on the initial load
        synchronized (this) { return allCodes.size(); }
    }

    public int getSuccessCount() {
        if (!loaded) return 0; // never block the UI thread on the initial load
        synchronized (this) { return successCodes.size(); }
    }

    public int getInvalidCount() {
        if (!loaded) return 0; // never block the UI thread on the initial load
        synchronized (this) { return invalidCodes.size(); }
    }

    public int getAlreadyCount() {
        if (!loaded) return 0; // never block the UI thread on the initial load
        synchronized (this) { return alreadyCodes.size(); }
    }

    /** Codes in the CURRENT list not tested yet (history can include codes from older lists). */
    public int getRemainingCount() {
        if (!loaded) return 0;
        synchronized (this) {
            if (remainingCache < 0) {
                int n = 0;
                for (String c : allCodes) {
                    String u = c.toUpperCase(Locale.US); // once per code
                    if (!successCodes.contains(u) && !invalidCodes.contains(u) && !alreadyCodes.contains(u)
                            && !deadCodes.contains(u)) n++;
                }
                remainingCache = n;
            }
            return remainingCache;
        }
    }

    private boolean isTested(String code) {
        String norm = code.toUpperCase(Locale.US);
        return successCodes.contains(norm) || invalidCodes.contains(norm) || alreadyCodes.contains(norm);
    }

    /** Priority keys (bundled + from the server) in try order, no duplicates. */
    private List<String> priorityCodes() {
        LinkedHashMap<String, String> m = new LinkedHashMap<>();
        for (String c : PRIORITY_CODES) m.put(c.toUpperCase(Locale.US), c);
        for (String c : serverPriority) m.putIfAbsent(c.toUpperCase(Locale.US), c);
        return new ArrayList<>(m.values());
    }

    public synchronized String getNextCode() {
        for (String code : priorityCodes()) {
            String u = code.toUpperCase(Locale.US);
            if (successCodes.contains(u) || alreadyCodes.contains(u) || deferredCodes.contains(u)) continue;
            // A priority code marked invalid gets ONE more try: a misread (an old error line,
            // a slow answer) must not cost a user the codes everyone is meant to get.
            if (invalidCodes.contains(u)) {
                if (priorityRechecked.contains(u)) continue;
                priorityRechecked.add(u);
                invalidCodes.remove(u);
                pendingInvalid.remove(u);
                remainingCache = -1;
                saveState();
                Log.i(TAG, "Re-checking priority code " + code + " (was marked invalid)");
            }
            return code;
        }
        // Main list: a random untested code every time, never the next one in line, so no two
        // phones (or two runs on one phone) work through the codes in the same order.
        int n = allCodes.size();
        if (n == 0) return null;
        for (int tries = 0; tries < 64; tries++) {
            String code = allCodes.get(picker.nextInt(n));
            if (isUntried(code)) return code;
        }
        // Mostly tested already: walk from a random start so the pick is still spread out.
        int start = picker.nextInt(n);
        for (int k = 0; k < n; k++) {
            String code = allCodes.get((start + k) % n);
            if (isUntried(code)) return code;
        }
        return null;
    }

    private boolean isUntried(String code) {
        String u = code.toUpperCase(Locale.US);
        return !isTested(code) && !deferredCodes.contains(u) && !deadCodes.contains(u);
    }

    public synchronized void defer(String code) {
        if (code != null) deferredCodes.add(code.toUpperCase(Locale.US));
    }

    public synchronized void markSuccess(String code) {
        if (code != null) {
            String c = code.toUpperCase(Locale.US);
            if (successCodes.add(c)) Stats.codeAdded(context);
            invalidCodes.remove(c);
            alreadyCodes.remove(c);
            pendingAlready.remove(c);
            pendingSuccess.add(c);
            pendingInvalid.remove(c);
            remainingCache = -1;
            CodeSync.syncSoon(context, CodeSync.PUSH_DELAY_MS);
            saveState();
            notifyListeners();
        }
    }

    /** Nusuk: "already used" = this account has this code. Skipped from now on, on every phone sync. */
    public synchronized void markAlready(String code) {
        if (code == null) return;
        String c = code.toUpperCase(Locale.US);
        if (successCodes.contains(c)) return;
        invalidCodes.remove(c);
        pendingInvalid.remove(c);
        if (alreadyCodes.add(c)) pendingAlready.add(c);
        remainingCache = -1;
        CodeSync.syncSoon(context, CodeSync.PUSH_DELAY_MS);
        saveState();
        notifyListeners();
    }

    public synchronized void markInvalid(String code) {
        if (code != null) {
            String c = code.toUpperCase(Locale.US);
            if (!successCodes.contains(c) && !alreadyCodes.contains(c)) {
                invalidCodes.add(c);
                pendingInvalid.add(c);
            }
            remainingCache = -1;
            CodeSync.syncSoon(context, CodeSync.PUSH_DELAY_MS);
            saveState();
            notifyListeners();
        }
    }

    public synchronized void resetStats() {
        successCodes.clear();
        invalidCodes.clear();
        alreadyCodes.clear();
        deferredCodes.clear();
        currentIndex = 0;
        remainingCache = -1;
        saveState();
        notifyListeners();
    }

    /** Cursor of the code download (server key id); 0 = nothing downloaded yet. */
    synchronized long codesCursor() {
        return codesCursor;
    }

    /** Adds a downloaded page (new keys only, letter case ignored) and saves it with the new cursor. */
    synchronized int appendCodes(List<String> page, long cursor) {
        if (codeKeys == null) { // built once, kept in step with allCodes afterwards
            codeKeys = new HashSet<>(allCodes.size() * 2 + page.size());
            for (String c : allCodes) codeKeys.add(c.toUpperCase(Locale.US));
        }
        int added = 0;
        for (String c : page) {
            if (c.startsWith("NG-") && codeKeys.add(c.toUpperCase(Locale.US))) { allCodes.add(c); added++; }
        }
        codesCursor = Math.max(codesCursor, cursor);
        remainingCache = -1;
        saveCodes();
        saveState();
        if (added > 0) notifyListeners();
        return added;
    }

    // ---- persistence ----
    // keys_state.json = progress (index + success + invalid): small, rewritten after results,
    // debounced and written off the calling thread. keys_codes.json = the code list: large,
    // written only when the list itself changes. Both via AtomicFile, so a kill mid-write
    // leaves the previous good copy instead of a corrupt file.
    private static final String CODES_FILE = "keys_codes.json";
    private static final long SAVE_DEBOUNCE_MS = 500;
    private final ScheduledExecutorService io = Executors.newSingleThreadScheduledExecutor();
    private boolean savePending = false; // a write is queued and hasn't read the data yet

    /** Schedules a progress save; many results within SAVE_DEBOUNCE_MS become one write. */
    private synchronized void saveState() {
        if (savePending) return; // the queued write will read the latest data
        savePending = true;
        io.schedule(this::writeState, SAVE_DEBOUNCE_MS, TimeUnit.MILLISECONDS);
    }

    private void writeState() {
        String data;
        synchronized (this) {
            savePending = false; // changes from here on schedule another write
            try {
                JSONObject json = new JSONObject();
                json.put("currentIndex", currentIndex);
                json.put("codesVersion", codesVersion);
                json.put("codesCursor", codesCursor);
                JSONArray succArr = new JSONArray();
                for (String s : successCodes) succArr.put(s);
                json.put("success", succArr);
                JSONArray invArr = new JSONArray();
                for (String s : invalidCodes) invArr.put(s);
                json.put("invalid", invArr);
                json.put("pendingSuccess", new JSONArray(pendingSuccess));
                json.put("pendingInvalid", new JSONArray(pendingInvalid));
                json.put("already", new JSONArray(alreadyCodes));
                json.put("pendingAlready", new JSONArray(pendingAlready));
                json.put("dead", new JSONArray(deadCodes));
                json.put("deadSince", deadSince);
                json.put("serverPriority", new JSONArray(serverPriority));
                json.put("restored", restored);
                json.put("priorityRechecked", new JSONArray(priorityRechecked));
                data = json.toString();
            } catch (Exception e) {
                Log.e(TAG, "Error building state", e);
                return;
            }
        }
        writeAtomic(STATE_FILE, data);
    }

    private synchronized void saveCodes() {
        final List<String> snap = new ArrayList<>(allCodes); // cheap copy; the slow part runs off the lock
        io.execute(() -> {
            JSONArray codesArr = new JSONArray();
            for (String s : snap) codesArr.put(s);
            writeAtomic(CODES_FILE, codesArr.toString());
        });
    }

    private void writeAtomic(String name, String data) {
        AtomicFile f = new AtomicFile(new File(context.getFilesDir(), name));
        FileOutputStream out = null;
        try {
            out = f.startWrite();
            out.write(data.getBytes(StandardCharsets.UTF_8));
            f.finishWrite(out);
        } catch (Exception e) {
            Log.e(TAG, "Error saving " + name, e);
            if (out != null) f.failWrite(out);
        }
    }

    private String readAtomic(String name) {
        AtomicFile f = new AtomicFile(new File(context.getFilesDir(), name));
        if (!f.getBaseFile().exists()) return null;
        try {
            return new String(f.readFully(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.e(TAG, "Error reading " + name, e);
            return null;
        }
    }

    private void loadState() {
        String raw = readAtomic(STATE_FILE);
        if (raw != null) try {
            JSONObject json = new JSONObject(raw);

            currentIndex = json.optInt("currentIndex", 0);
            codesVersion = json.optInt("codesVersion", 1); // saved before versioning = list v1
            codesCursor = json.optLong("codesCursor", 0);

            JSONArray succArr = json.optJSONArray("success");
            if (succArr != null) {
                for (int i = 0; i < succArr.length(); i++) {
                    successCodes.add(succArr.getString(i));
                }
            }

            JSONArray invArr = json.optJSONArray("invalid");
            if (invArr != null) {
                for (int i = 0; i < invArr.length(); i++) {
                    invalidCodes.add(invArr.getString(i));
                }
            }

            readInto(json.optJSONArray("pendingSuccess"), pendingSuccess);
            readInto(json.optJSONArray("pendingInvalid"), pendingInvalid);
            readInto(json.optJSONArray("already"), alreadyCodes);
            readInto(json.optJSONArray("pendingAlready"), pendingAlready);
            readInto(json.optJSONArray("dead"), deadCodes);
            deadSince = json.optLong("deadSince", 0);
            readInto(json.optJSONArray("serverPriority"), serverPriority);
            restored = json.optBoolean("restored", false);
            readInto(json.optJSONArray("priorityRechecked"), priorityRechecked);

            // Older versions kept the code list inside the state file: migrate it once.
            JSONArray legacy = json.optJSONArray("codes");
            if (legacy != null && legacy.length() > 0 && readAtomic(CODES_FILE) == null) {
                for (int i = 0; i < legacy.length(); i++) allCodes.add(legacy.getString(i));
                saveCodes();
                saveState(); // rewrites the state file without the big list
            }
        } catch (Exception e) {
            Log.e(TAG, "Error loading state", e);
        }

        String codesRaw = readAtomic(CODES_FILE);
        if (codesRaw != null && allCodes.isEmpty()) try {
            JSONArray codesArr = new JSONArray(codesRaw);
            for (int i = 0; i < codesArr.length(); i++) allCodes.add(codesArr.getString(i));
        } catch (Exception e) {
            Log.e(TAG, "Error loading codes", e);
        }
    }

    private static void readInto(JSONArray arr, Collection<String> out) throws org.json.JSONException {
        if (arr == null) return;
        for (int i = 0; i < arr.length(); i++) out.add(arr.getString(i));
    }

    // ---- server backup (used by CodeSync, on its own thread) ----

    /** Before the first restore on this install, everything already tested goes up too. */
    synchronized boolean needsRestore() {
        return !restored;
    }

    /** Copies of everything tested: [0] = success, [1] = invalid, [2] = already used. */
    synchronized List<List<String>> snapshotTested() {
        return Arrays.asList(new ArrayList<>(successCodes), new ArrayList<>(invalidCodes), new ArrayList<>(alreadyCodes));
    }

    /** Up to `max` results not backed up yet: [0] = success, [1] = invalid, [2] = already used. */
    synchronized List<List<String>> nextPending(int max) {
        List<String> s = new ArrayList<>(), i = new ArrayList<>(), a = new ArrayList<>();
        for (String c : pendingSuccess) { if (s.size() >= max) break; s.add(c); }
        for (String c : pendingAlready) { if (s.size() + a.size() >= max) break; a.add(c); }
        for (String c : pendingInvalid) { if (s.size() + a.size() + i.size() >= max) break; i.add(c); }
        return Arrays.asList(s, i, a);
    }

    /** The server stored these results. */
    synchronized void onUploaded(List<String> success, List<String> invalid, List<String> already) {
        pendingSuccess.removeAll(success);
        pendingInvalid.removeAll(invalid);
        pendingAlready.removeAll(already);
        saveState();
    }

    synchronized long deadSince() {
        return deadSince;
    }

    /** Changes to the shared dead-code list since `deadSince`. */
    synchronized void onDead(List<String> dead, List<String> alive, long cursor) {
        if (dead.isEmpty() && alive.isEmpty() && cursor == deadSince) return;
        for (String c : dead) deadCodes.add(c.toUpperCase(Locale.US));
        for (String c : alive) deadCodes.remove(c.toUpperCase(Locale.US));
        deadSince = Math.max(deadSince, cursor);
        if (!dead.isEmpty() || !alive.isEmpty()) {
            Log.i(TAG, "Shared dead codes: +" + dead.size() + " -" + alive.size() + " = " + deadCodes.size());
            remainingCache = -1;
            notifyListeners();
        }
        saveState();
    }

    synchronized void onPriority(List<String> priority) {
        if (priority == null || priority.equals(serverPriority)) return;
        serverPriority.clear();
        serverPriority.addAll(priority);
        saveState();
    }

    /** The server's whole saved history for this phone (e.g. from before a reinstall). */
    synchronized void onRestored(List<String> serverSuccess, List<String> serverInvalid, List<String> serverAlready) {
        int before = successCodes.size() + invalidCodes.size() + alreadyCodes.size();
        for (String c : serverSuccess) {
            String u = c.toUpperCase(Locale.US);
            successCodes.add(u);
            invalidCodes.remove(u);
            alreadyCodes.remove(u);
        }
        for (String c : serverAlready) {
            String u = c.toUpperCase(Locale.US);
            if (successCodes.contains(u)) continue;
            invalidCodes.remove(u);
            alreadyCodes.add(u);
        }
        for (String c : serverInvalid) {
            String u = c.toUpperCase(Locale.US);
            if (!successCodes.contains(u) && !alreadyCodes.contains(u)) invalidCodes.add(u);
        }
        restored = true;
        int added = successCodes.size() + invalidCodes.size() + alreadyCodes.size() - before;
        Log.i(TAG, "Server backup in sync" + (added > 0 ? ", restored " + added + " tested codes" : ""));
        remainingCache = -1;
        saveState();
        notifyListeners();
    }
}
