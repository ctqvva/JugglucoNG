'use strict';
/*
 * dump_ican_env.js (fixed) — read-only dump of the vendor iCan CGM SDK tenant
 * config from a running vendor app (CE: com.sino.care, RU: ru.luch.testcgm).
 *
 * Attach after launch:
 *   frida -U -p <pid> -l tools/ican-capture/dump_ican_env.js
 *
 * Rounds: every EnvConfigEnum getter + reflective instance field snapshot +
 * the log-SDK holder statics (base/flags). Only reads; no network, no writes.
 * Secrets print to the local console only — do not paste them publicly.
 */

function round() {
    var out = Java.perform(function () {
        var ok = 0;

        var E = null;
        try { E = Java.use('com.sinocare.cgm.cgm_sdk.cgm.EnvConfigEnum'); }
        catch (e) { console.log('[skip] EnvConfigEnum not assumed: ' + e); }

        if (E) {
            try {
                var vals = E.values();
                for (var i = 0; i < vals.length; i++) {
                    var v = vals[i];
                    var name = '?';
                    try { name = String(v.name()); } catch (e2) { name = '?' + i; }
                    var base = '?', appId = '?', secret = '<value?>';
                    try { base = String(v.getBaseUrl()); } catch (e2) { base = 'err ' + e2; }
                    try { appId = String(v.getSdkAppId()); } catch (e2) { appId = 'err ' + e2; }
                    try {
                        var s = String(v.getSdkAppSecret());
                        secret = (s === 'null') ? 'null' : ('len=' + s.length);
                        if (s.length >= 6 && s !== 'null') {
                            console.log('[env-secret-prefix]', name, s.substring(0, 6) + '…');
                        }
                    } catch (e2) { secret = 'err ' + e2; }
                    console.log('[env]', name, 'base="' + base + '"', 'appId="' + appId + '"', 'secret=' + secret);
                    ok += 1;
                }
            } catch (e) { console.log('[skip] enum values(): ' + e); }
        }

        try {
            Java.choose('com.sinocare.cgm.cgm_sdk.cgm.EnvConfigEnum', {
                onMatch: function (inst) {
                    try {
                        var cls = inst.getClass();
                        var fields = cls.getDeclaredFields();
                        for (var i = 0; i < fields.length; i++) {
                            fields[i].setAccessible(true);
                            console.log('[live]', String(fields[i].getName()), '=',
                                String(fields[i].get(inst)));
                            ok += 1;
                        }
                    } catch (e) { console.log('[skip] reflect: ' + e); }
                },
                onComplete: function () {}
            });
        } catch (e) { console.log('[skip] choose: ' + e); }

        try {
            var B = Java.use('com.sinocare.log.b');
            var inst = B.f4689a.value;
            var custom = inst.f4695h.value;
            var primary = inst.f4694g.value;
            console.log('[log] custom-base="' + String(custom) + '"', 'f-primary="' + String(primary) + '"');
            ok += 1;
        } catch (e) { console.log('[skip] log holder: ' + e); }

        try {
            var M = Java.use('com.sinocare.log.manager.CGMUploadEventLogManager');
            var m = M.f4778a.value;
            console.log('[log] flags debug/intl =', String(m.i()), String(m.j()));
            ok += 1;
        } catch (e) { console.log('[skip] log flags: ' + e); }

        return ok;
    });
    return out;
}

(function () {
    var ok = round();
    console.log('[*] hits(epoch1)=' + ok);
})();
