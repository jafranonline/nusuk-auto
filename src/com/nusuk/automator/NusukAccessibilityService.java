package com.nusuk.automator;

import com.nusuk.automator.automation.AutomationService;

/**
 * The accessibility service Android knows this app by. All of the work is in
 * {@link AutomationService}; this class only keeps the name.
 *
 * Do not rename or move it. Android stores "Nusuk Auto is switched on in Accessibility" under this
 * exact class name (com.nusuk.automator/.NusukAccessibilityService). A different name would switch
 * the service off on every phone at the next update, and each user would have to turn it on again.
 */
public final class NusukAccessibilityService extends AutomationService {
}
