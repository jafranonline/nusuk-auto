package com.nusuk.automator.notices;

import android.app.job.JobParameters;
import android.app.job.JobService;

/** Periodic background check for new announcements (see Notices). */
public class NoticeJob extends JobService {
    private volatile Thread worker;

    @Override
    public boolean onStartJob(JobParameters params) {
        worker = new Thread(() -> {
            boolean ok = Notices.check(getApplicationContext());
            jobFinished(params, !ok); // failed (offline...): let the system retry with backoff
        }, "NoticeJob");
        worker.start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        Thread t = worker;
        if (t != null) t.interrupt();
        return true;
    }
}
