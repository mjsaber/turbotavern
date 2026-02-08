const { withAndroidManifest } = require("@expo/config-plugins");

module.exports = function withOverlayService(config) {
  return withAndroidManifest(config, async (config) => {
    const manifest = config.modResults.manifest;

    if (!manifest["uses-permission"]) manifest["uses-permission"] = [];

    const existingPerms = manifest["uses-permission"].map(
      (p) => p.$["android:name"]
    );

    const permsToAdd = [
      "android.permission.SYSTEM_ALERT_WINDOW",
      "android.permission.FOREGROUND_SERVICE",
      "android.permission.FOREGROUND_SERVICE_SPECIAL_USE",
      "android.permission.FOREGROUND_SERVICE_MICROPHONE",
      "android.permission.RECORD_AUDIO",
    ];

    for (const perm of permsToAdd) {
      if (!existingPerms.includes(perm)) {
        manifest["uses-permission"].push({
          $: { "android:name": perm },
        });
      }
    }

    const app = manifest.application[0];
    if (!app.service) app.service = [];

    const hasService = app.service.some(
      (s) => s.$["android:name"] === ".overlay.OverlayService"
    );

    if (!hasService) {
      app.service.push({
        $: {
          "android:name": ".overlay.OverlayService",
          "android:foregroundServiceType": "specialUse|microphone",
          "android:exported": "false",
        },
      });
    }

    return config;
  });
};
