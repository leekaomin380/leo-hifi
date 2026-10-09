/*
 * Host-side fault-injection harness for the Leo HiFi controller.
 *
 * This is NOT an Android build.  It links the unmodified leo_hifi.c from the
 * patched tree against mock tinyalsa / property / log surfaces so that the
 * controller's decision logic can be exercised against failures that cannot be
 * injected on the device.  It proves logic, not buildability of the HAL.
 */
#include <stdio.h>
#include <errno.h>
#include <string.h>
#include <stdlib.h>
#include <sys/stat.h>
#include <unistd.h>
#include "mock.h"
#include "leo_hifi.h"

#ifndef ESS_DIR
#define ESS_DIR "/tmp/leo-ess-mock"
#endif
#define ESS_PATH ESS_DIR "/driver"

static int g_pass, g_fail;

static void ok(const char *what, int cond)
{
    if (cond) { g_pass++; printf("  PASS  %s\n", what); }
    else      { g_fail++; printf("  FAIL  %s\n", what); }
}

static void ess_present(int on)
{
    mkdir(ESS_DIR, 0755);
    if (on) { FILE *f = fopen(ESS_PATH, "w"); if (f) fclose(f); }
    else    { unlink(ESS_PATH); }
}

/* Build the control set the real card exposes. */
static void card_default(void)
{
    int i;
    char n[64];

    mock_reset();
    mock_add_int ("Volume", 2, 205, 205);
    mock_add_enum("QUAT_MI2S BitWidth",  "S24_LE");
    mock_add_enum("QUAT_MI2S SampleRate","KHZ_48");
    mock_add_int ("HPHL DAC Switch", 1, 0, 0);
    mock_add_enum("SLIM RX1 MUX", "ZERO");
    mock_add_enum("SLIM RX2 MUX", "ZERO");
    mock_add_enum("RX1 MIX1 INP1", "ZERO");
    mock_add_enum("RX2 MIX1 INP1", "ZERO");
    mock_add_enum("CLASS_H_DSM MUX", "ZERO");
    for (i = 1; i <= LEO_HIFI_MM_COUNT; i++) {
        snprintf(n, sizeof(n), "SLIMBUS_0_RX Audio Mixer MultiMedia%d", i);
        mock_add_int(n, 1, 0, 0);
        if (i == 9) continue;                 /* the card has no QUAT MM9 */
        snprintf(n, sizeof(n), "QUAT_MI2S_RX Audio Mixer MultiMedia%d", i);
        mock_add_int(n, 1, 0, 0);
    }
    ess_present(1);
}

/* Simulate what enable_audio_route() would have done for the HiFi path. */
static void apply_hifi_path(void)
{
    mock_set_int("QUAT_MI2S_RX Audio Mixer MultiMedia1", 1, 0);
}

/* Simulate the ordinary "headphones" + "deep-buffer-playback" paths. */
static void apply_standard_path(void)
{
    mock_set_int ("SLIMBUS_0_RX Audio Mixer MultiMedia1", 1, 0);
    mock_set_enum("SLIM RX1 MUX", "AIF1_PB");
    mock_set_enum("SLIM RX2 MUX", "AIF1_PB");
    mock_set_enum("RX1 MIX1 INP1", "RX1");
    mock_set_enum("RX2 MIX1 INP1", "RX2");
    mock_set_enum("CLASS_H_DSM MUX", "DSM_HPHL_RX1");
    mock_set_int ("HPHL DAC Switch", 1, 0);
}

static struct leo_hifi lh;
static int initialized;
static void test_init(const char *device) {
    if (initialized) leo_hifi_destroy(&lh);
    leo_hifi_init(&lh, mock_mixer(), device);
    initialized = 1;
}


static void init_enabled(void)
{
    mock_prop_put(LEO_PROP_ENABLE, "true");
    test_init( LEO_HIFI_DEVICE_NAME);
}

int main(void)
{
    const char *sign; int whole, tenth;
    char status[512];
    int v[2];
    int i;

    printf("== S0  dB formatting boundaries (sign-safe integer path) ==\n");
    {
        struct { int ctl; const char *want; } t[] = {
            {   0, "-127.5" }, {   1, "-127.0" }, { 204, "-25.5" },
            { 205, "-25.0"  }, { 213, "-21.0"  }, { 225, "-15.0" },
            { 229, "-13.0"  }, { 253, "-1.0"   }, { 254, "-0.5"  },
            { 255, "0.0"    },
        };
        for (i = 0; i < (int)(sizeof(t)/sizeof(t[0])); i++) {
            char got[32];
            leo_hifi_ctl_to_db(t[i].ctl, &sign, &whole, &tenth);
            snprintf(got, sizeof(got), "%s%d.%d", sign, whole, tenth);
            printf("     ctl=%3d -> %-8s (want %s)\n", t[i].ctl, got, t[i].want);
            ok("dB formatting", strcmp(got, t[i].want) == 0);
        }
    }

    printf("== S1  runtime flag OFF: controller never selects HiFi ==\n");
    card_default();
    mock_prop_put(LEO_PROP_ENABLE, "false");
    test_init( LEO_HIFI_DEVICE_NAME);
    ok("probe succeeded", lh.supported);
    ok("route not wanted", !leo_hifi_route_wanted(&lh));
    ok("C: init restores the fail-quiet floor", mock_get_int("Volume", 0) == LEO_HIFI_CTL_FLOOR);
    ok("QUAT BitWidth untouched", strcmp(mock_get_enum("QUAT_MI2S BitWidth"), "S24_LE") == 0);

    printf("== S2  nominal enable -> HIFI_ACTIVE ==\n");
    card_default();
    init_enabled();
    ok("route wanted", leo_hifi_route_wanted(&lh));
    ok("backend set ok", leo_hifi_set_backend(&lh) == 0);
    apply_hifi_path();
    leo_hifi_on_route(&lh, true);
    ok("state == HIFI_ACTIVE", lh.effective == LEO_HIFI_ACTIVE);
    ok("no bypass observed", lh.bypass == 0);
    ok("generation advanced", lh.generation == 1);

    printf("== S3  Volume control missing ==\n");
    card_default(); mock_remove("Volume");
    init_enabled();
    ok("probe failed", !lh.supported);
    ok("fail_code == CTL_MISSING", lh.fail_code == LEO_FAIL_CTL_MISSING);
    ok("route refused", !leo_hifi_route_wanted(&lh) || lh.supported);
    ok("not permanent", !lh.permanent_fail);

    printf("== S4  QUAT SampleRate control missing ==\n");
    card_default(); mock_remove("QUAT_MI2S SampleRate");
    init_enabled();
    ok("probe failed", !lh.supported);
    ok("set_backend refuses", leo_hifi_set_backend(&lh) != 0);

    printf("== S5  QUAT BitWidth control missing ==\n");
    card_default(); mock_remove("QUAT_MI2S BitWidth");
    init_enabled();
    ok("probe failed", !lh.supported);

    printf("== S6  ESS sysfs absent, then late re-probe ==\n");
    card_default(); ess_present(0);
    init_enabled();
    ok("probe failed", !lh.supported);
    ok("fail_code == ESS_UNBOUND", lh.fail_code == LEO_FAIL_ESS_UNBOUND);
    ok("route refused (attempt 2)", !leo_hifi_route_wanted(&lh));
    ess_present(1);
    ok("late re-probe succeeds (attempt 3)", leo_hifi_route_wanted(&lh));
    ok("supported now true", lh.supported);

    printf("== S6b re-probe budget is bounded ==\n");
    card_default(); ess_present(0);
    init_enabled();
    for (i = 0; i < 10; i++) (void) leo_hifi_route_wanted(&lh);
    ok("attempts capped at LEO_HIFI_PROBE_MAX",
       lh.probe_attempts == LEO_HIFI_PROBE_MAX);
    ess_present(1);
    ok("budget exhausted -> still refused", !leo_hifi_route_wanted(&lh));

    printf("== S6c structural mismatch is permanent, never retried ==\n");
    card_default();
    mock_prop_put(LEO_PROP_ENABLE, "true");
    test_init( "speaker-protected");   /* wrong table entry */
    ok("permanent_fail set", lh.permanent_fail);
    ok("fail_code == TABLE_MISMATCH", lh.fail_code == LEO_FAIL_TABLE_MISMATCH);
    for (i = 0; i < 5; i++) (void) leo_hifi_route_wanted(&lh);
    ok("no retry burned", lh.probe_attempts == 1);

    printf("== S7  write succeeds but read-back diverges ==\n");
    card_default(); init_enabled();
    mock_skew_readback("Volume", +3);
    ok("apply_volume reports EIO", leo_hifi_apply_volume(&lh, 10) == -5 /*-EIO*/);
    ok("fail_code == READBACK_SOFT", lh.fail_code == LEO_FAIL_READBACK_SOFT);
    ok("vol_applied cleared", !lh.vol_applied);

    printf("== S8  Volume request out of range ==\n");
    card_default(); init_enabled();
    ok("negative rejected", leo_hifi_apply_volume(&lh, -1) == -22 /*-EINVAL*/);
    ok("above cap rejected", leo_hifi_apply_volume(&lh, LEO_HIFI_VOL_MAX + 1) == -22);
    ok("fail_code == VOL_RANGE", lh.fail_code == LEO_FAIL_VOL_RANGE);
    ok("Volume unchanged at floor", mock_get_int("Volume", 0) == LEO_HIFI_CTL_FLOOR);

    printf("== S8b in-range apply ramps and lands exactly ==\n");
    card_default(); init_enabled();
    ok("apply 0 ok", leo_hifi_apply_volume(&lh, 0) == 0);
    ok("C: user 0 == ctl 135 (-60 dB)", mock_get_int("Volume", 0) == 135 && mock_get_int("Volume", 1) == 135);
    ok("apply 30 ok", leo_hifi_apply_volume(&lh, 30) == 0);
    ok("C: user 30 == ctl 195 (-30 dB)", mock_get_int("Volume", 0) == 195);
    ok("never exceeded the ceiling", mock_get_int("Volume", 0) <= LEO_HIFI_CTL_CEIL);

    printf("== S9  backend half-set: BitWidth ok, SampleRate write fails ==\n");
    card_default(); init_enabled();
    mock_fail_write("QUAT_MI2S SampleRate", 1);
    ok("set_backend fails", leo_hifi_set_backend(&lh) != 0);
    ok("fail_code == CTL_WRITE", lh.fail_code == LEO_FAIL_CTL_WRITE);
    apply_hifi_path();
    leo_hifi_on_route(&lh, true);
    ok("state == ERROR_FALLBACK", lh.effective == LEO_HIFI_ERROR_FALLBACK);
    ok("E6 not set", (lh.evidence & LEO_EV_E6) == 0);

    printf("== S9b backend enum read-back diverges ==\n");
    card_default(); init_enabled();
    mock_enum_readback("QUAT_MI2S SampleRate", "KHZ_44P1");
    ok("set_backend fails", leo_hifi_set_backend(&lh) != 0);
    ok("fail_code == BACKEND_READBACK", lh.fail_code == LEO_FAIL_BACKEND_READBACK);

    printf("== S10 MultiMedia5 appears, analog outlet cut -> recorded, NOT fatal ==\n");
    card_default(); init_enabled();
    apply_hifi_path();
    mock_set_int("SLIMBUS_0_RX Audio Mixer MultiMedia5", 1, 0);   /* outlet stays Off */
    leo_hifi_on_route(&lh, true);
    ok("bypass attempt recorded", (lh.bypass & LEO_BP_SLIM_FE) != 0);
    ok("E5 still set", (lh.evidence & LEO_EV_E5) != 0);
    ok("state stays HIFI_ACTIVE", lh.effective == LEO_HIFI_ACTIVE);

    printf("== S11 analog outlet open -> FATAL ==\n");
    card_default(); init_enabled();
    apply_hifi_path();
    mock_set_int("HPHL DAC Switch", 1, 0);
    leo_hifi_on_route(&lh, true);
    ok("outlet bit set", (lh.bypass & LEO_BP_OUTLET) != 0);
    ok("fail_code == WCD_BYPASS", lh.fail_code == LEO_FAIL_WCD_BYPASS);
    ok("state == ERROR_FALLBACK", lh.effective == LEO_HIFI_ERROR_FALLBACK);

    printf("== S12 second playback usecase on SLIMBUS with outlet open ==\n");
    card_default(); init_enabled();
    apply_hifi_path();
    apply_standard_path();                       /* notification stream, full chain */
    leo_hifi_on_route(&lh, true);
    ok("all chain bits observed",
       (lh.bypass & (LEO_BP_SLIM_FE|LEO_BP_MUX_LIVE|LEO_BP_MIX_LIVE|LEO_BP_CLASSH|LEO_BP_OUTLET))
       == (LEO_BP_SLIM_FE|LEO_BP_MUX_LIVE|LEO_BP_MIX_LIVE|LEO_BP_CLASSH|LEO_BP_OUTLET));
    ok("state == ERROR_FALLBACK", lh.effective == LEO_HIFI_ERROR_FALLBACK);

    printf("== S13 ordinary wired standard route is never an error ==\n");
    card_default(); init_enabled();
    apply_standard_path();                       /* WCD chain fully live: normal! */
    leo_hifi_on_route(&lh, false);
    ok("state == WIRED_STANDARD or IDLE",
       lh.effective == LEO_HIFI_WIRED_STANDARD || lh.effective == LEO_HIFI_IDLE);
    ok("no WCD_BYPASS fail code", lh.fail_code != LEO_FAIL_WCD_BYPASS);
    ok("no bypass bits recorded", lh.bypass == 0);

    printf("== S13b ERROR_FALLBACK clears when we route back to standard ==\n");
    card_default(); init_enabled();
    apply_hifi_path(); mock_set_int("HPHL DAC Switch", 1, 0);
    leo_hifi_on_route(&lh, true);
    ok("in ERROR_FALLBACK", lh.effective == LEO_HIFI_ERROR_FALLBACK);
    leo_hifi_on_route(&lh, false);
    ok("cleared to WIRED_STANDARD", lh.effective == LEO_HIFI_WIRED_STANDARD);

    printf("== S14 QUAT front end never came up (no mixer path for the usecase) ==\n");
    card_default(); init_enabled();
    /* no apply_hifi_path() -- every QUAT MM stays Off */
    leo_hifi_on_route(&lh, true);
    ok("E4 not set", (lh.evidence & LEO_EV_E4) == 0);
    ok("state == ERROR_FALLBACK", lh.effective == LEO_HIFI_ERROR_FALLBACK);

    printf("== S15 HAL restart: fresh init clears all state ==\n");
    card_default(); init_enabled();
    apply_hifi_path(); leo_hifi_on_route(&lh, true);
    ok("active before restart", lh.effective == LEO_HIFI_ACTIVE);
    card_default();                              /* audio_route_init re-baselines */
    init_enabled();
    ok("generation reset", lh.generation == 0);
    ok("state == IDLE", lh.effective == LEO_HIFI_IDLE);
    ok("evidence cleared", lh.evidence == 0);
    ok("C: restart restores fail-quiet floor", mock_get_int("Volume", 0) == LEO_HIFI_CTL_FLOOR);

    printf("== S16 property_set failing (no property_contexts yet) ==\n");
    card_default();
    mock_property_set_fails = 1;
    mock_prop_put(LEO_PROP_ENABLE, "true");      /* seeded before the failure flag */
    mock_property_set_fails = 0;
    mock_prop_put(LEO_PROP_ENABLE, "true");
    mock_property_set_fails = 1;
    test_init( LEO_HIFI_DEVICE_NAME);
    ok("init still succeeds", lh.supported);
    ok("intent still honoured", leo_hifi_route_wanted(&lh));
    ok("volume path still works", leo_hifi_apply_volume(&lh, 0) == 0);
    mock_property_set_fails = 0;

    printf("== S17 runtime switch flipped during playback ==\n");
    card_default(); init_enabled();
    apply_hifi_path(); leo_hifi_on_route(&lh, true);
    ok("active", lh.effective == LEO_HIFI_ACTIVE);
    ok("set_mode(false) reports a change", leo_hifi_set_mode(&lh, false));
    ok("route no longer wanted", !leo_hifi_route_wanted(&lh));
    ok("set_mode(false) again reports no change", !leo_hifi_set_mode(&lh, false));

    printf("== S18 status string is well formed and reports ACDB as expected-absent ==\n");
    card_default(); init_enabled();
    apply_hifi_path(); leo_hifi_on_route(&lh, true);
    leo_hifi_status_string(&lh, status, sizeof(status));
    printf("     %s\n", status);
    ok("has effective:hifi_active", strstr(status, "effective:hifi_active") != NULL);
    ok("has acdb:absent_expected", strstr(status, "acdb:absent_expected") != NULL);
    ok("has backend:S24_LE/KHZ_48", strstr(status, "backend:S24_LE/KHZ_48") != NULL);
    ok("has vol_db:-25.0", strstr(status, "vol_db:-25.0") != NULL);

    printf("== S19 read_volume never writes ==\n");
    card_default(); init_enabled();
    ok("read ok", leo_hifi_read_volume(&lh, v) == 0);
    ok("C: value is the fail-quiet floor after init", v[0] == LEO_HIFI_CTL_FLOOR && v[1] == LEO_HIFI_CTL_FLOOR);
    ok("unchanged after read", mock_get_int("Volume", 0) == LEO_HIFI_CTL_FLOOR);

    printf("== S20 schema3 fresh read-only evidence ==\n");
    card_default(); init_enabled(); apply_hifi_path(); leo_hifi_on_route(&lh, true);
    unsigned long long session = lh.session;
    unsigned long long token = leo_hifi_flow_token(&lh);
    leo_hifi_note_frames(&lh, token, 1, 1024, 1024, 512, true);
    leo_hifi_note_frames(&lh, token, 1, 1536, 1024, 512, true);
    leo_hifi_note_frames(&lh, token, 1, 2048, 1024, 512, true);
    leo_hifi_status_string(&lh, status, sizeof(status));
    ok("C: schema5 with hardvol", strstr(status,"schema:5,") != NULL && strstr(status,",hardvol:0") != NULL);
    ok("fresh MM1 live flow", strstr(status,"live:1,flow:1,") != NULL);
    ok("status leaves volume at 205", mock_get_int("Volume",0)==205);
    int writes_before = mock_write_count;
    int props_before = mock_property_write_count;
    mock_set_int("HPHL DAC Switch", -1, 0);
    leo_hifi_status_string(&lh, status, sizeof(status));
    ok("analog read failure fails closed", strstr(status,"live:0,flow:0,") != NULL);
    mock_set_int("HPHL DAC Switch", 1, 0);
    leo_hifi_status_string(&lh, status, sizeof(status));
    ok("analog outlet on fails closed", strstr(status,"live:0,flow:0,") != NULL);
    ok("status never repairs outlet", mock_get_int("HPHL DAC Switch",0)==1);
    mock_set_int("HPHL DAC Switch",0,0);
    mock_set_enum("QUAT_MI2S SampleRate", "KHZ_96");
    leo_hifi_status_string(&lh,status,sizeof(status));
    ok("backend drift is unconfirmed", strstr(status,"backend:unconfirmed,") != NULL);
    ok("status never repairs backend", strcmp(mock_get_enum("QUAT_MI2S SampleRate"),"KHZ_96")==0);
    mock_set_enum("QUAT_MI2S SampleRate", "KHZ_48");
    mock_set_int("QUAT_MI2S_RX Audio Mixer MultiMedia1",0,0);
    mock_set_int("QUAT_MI2S_RX Audio Mixer MultiMedia5",1,0);
    leo_hifi_status_string(&lh,status,sizeof(status));
    ok("MM5 cannot borrow MM1 flow", strstr(status,"live:0,flow:0,") != NULL);
    mock_set_int("QUAT_MI2S_RX Audio Mixer MultiMedia1",1,0);
    ess_present(0); leo_hifi_status_string(&lh,status,sizeof(status));
    ok("ESS disappearance fails closed", strstr(status,"live:0,flow:0,") != NULL);
    ess_present(1);
    mock_set_int("Volume",205,206); leo_hifi_status_string(&lh,status,sizeof(status));
    ok("mismatched channels fail closed", strstr(status,"live:0,flow:0,") != NULL);
    ok("queries made no mixer writes",mock_write_count==writes_before);
    ok("queries made no property writes",mock_property_write_count==props_before);
    leo_hifi_on_route_off(&lh);
    ok("route reset retains HAL session",lh.session==session);
    ok("route reset changes flow token",leo_hifi_flow_token(&lh)!=token);
    leo_hifi_note_frames(&lh,token,1,3000,1024,512,true);
    leo_hifi_status_string(&lh,status,sizeof(status));
    ok("late old-route flow cannot relight",strstr(status,"live:0,flow:0,")!=NULL);

    printf("== S21 schema3 guarded transactions, zero writes on rejection ==\n");
    card_default(); init_enabled(); apply_hifi_path(); leo_hifi_on_route(&lh, true);
    token = leo_hifi_flow_token(&lh);
    leo_hifi_note_frames(&lh, token, 1, 1024, 1024, 512, true);
    leo_hifi_note_frames(&lh, token, 1, 1536, 1024, 512, true);
    leo_hifi_note_frames(&lh, token, 1, 2048, 1024, 512, true);
    char sess[32], gen[32], old_gen[32];
    snprintf(sess, sizeof(sess), "%llu", lh.session);
    snprintf(gen, sizeof(gen), "%llu", lh.generation);
    snprintf(old_gen, sizeof(old_gen), "%llu", lh.generation - 1);
    struct { const char *mode, *vol, *session, *generation; int error; } bad[] = {
        {NULL,"malformed",sess,gen,-EINVAL}, {NULL,"",sess,gen,-EINVAL},
        {NULL,"-1",sess,gen,-EINVAL}, {NULL,"+0",sess,gen,-EINVAL},
        {NULL," 0",sess,gen,-EINVAL}, {NULL,"0 ",sess,gen,-EINVAL},
        {NULL,"00",sess,gen,-EINVAL}, {NULL,"61",sess,gen,-EINVAL},
        {NULL,"184467440737095516160",sess,gen,-EINVAL},
        {NULL,"999999999999999999999999999999999",sess,gen,-EINVAL},
        {"invalid",NULL,sess,gen,-EINVAL}, {"TRUE",NULL,sess,gen,-EINVAL},
        {"true","30",sess,gen,-EINVAL}, {NULL,NULL,sess,gen,-EINVAL},
        {"true",NULL,NULL,gen,-EINVAL}, {"true",NULL,sess,NULL,-EINVAL},
        {"true",NULL,"0",gen,-EINVAL}, {"true",NULL,"-1",gen,-EINVAL},
        {"true",NULL,"9223372036854775808",gen,-EINVAL},
        {"true",NULL,"184467440737095516160",gen,-EINVAL},
        {"true",NULL,sess,"-1",-EINVAL}, {"true",NULL,sess,"1x",-EINVAL},
        {NULL,"0","1",gen,-EAGAIN}, {NULL,"0",sess,old_gen,-EAGAIN},
        {"false",NULL,sess,old_gen,-EAGAIN}
    };
    for (i=0; i<(int)(sizeof(bad)/sizeof(bad[0])); i++) {
        int nw=mock_write_count, np=mock_property_write_count;
        bool changed=true; int vol_before=mock_get_int("Volume",0);
        int r=leo_hifi_process_request(&lh,bad[i].mode,bad[i].vol,bad[i].session,bad[i].generation,&changed);
        ok("invalid/stale request returns exact error",r==bad[i].error);
        ok("rejected request has no mixer/property/state write",mock_write_count==nw && mock_property_write_count==np && !changed && mock_get_int("Volume",0)==vol_before && lh.requested);
    }
    bool changed=false;
    int before_write=mock_write_count, before_prop=mock_property_write_count;
    lh.requested=false;
    ok("inactive intent rejects volume",leo_hifi_process_request(&lh,NULL,"0",sess,gen,&changed)==-EAGAIN);
    lh.requested=true;
    mock_set_int("HPHL DAC Switch",1,0);
    ok("live route drift rejects volume",leo_hifi_process_request(&lh,NULL,"0",sess,gen,&changed)==-EAGAIN);
    mock_set_int("HPHL DAC Switch",0,0);
    ok("inactive/drift rejection never writes",mock_write_count==before_write && mock_property_write_count==before_prop);
    ok("valid explicit zero succeeds",leo_hifi_process_request(&lh,NULL,"0",sess,gen,&changed)==0);
    ok("C: zero is DAC135 (-60 dB), not mute",mock_get_int("Volume",0)==135 && mock_get_int("Volume",1)==135);
    ok("C: old cap 60 now out of range",leo_hifi_process_request(&lh,NULL,"60",sess,gen,&changed)==-EINVAL);
    ok("valid cap succeeds",leo_hifi_process_request(&lh,NULL,"51",sess,gen,&changed)==0);
    ok("cap remains237",mock_get_int("Volume",0)==237 && mock_get_int("Volume",1)==237);
    mock_fail_write("Volume",1);
    ok("mixer error propagates",leo_hifi_process_request(&lh,NULL,"30",sess,gen,&changed)==-EIO);
    mock_fail_write("Volume",0); lh.fail_code=LEO_FAIL_NONE;
    leo_hifi_on_route_off(&lh);
    before_write=mock_write_count; before_prop=mock_property_write_count;
    ok("old route token rejected after change",leo_hifi_process_request(&lh,NULL,"0",sess,gen,&changed)==-EAGAIN);
    snprintf(gen,sizeof(gen),"%llu",lh.generation);
    ok("route change causes zero request writes",mock_write_count==before_write && mock_property_write_count==before_prop);
    ok("C: current token without route stores level only",leo_hifi_process_request(&lh,NULL,"20",sess,gen,&changed)==0
       && lh.vol_user==20 && mock_write_count==before_write
       && strcmp(mock_prop_get(LEO_PROP_VOLUME),"20")==0);
    ok("valid OFF operation accepted",leo_hifi_process_request(&lh,"false",NULL,sess,gen,&changed)==0 && changed && !lh.requested);

    leo_hifi_destroy(&lh);
    printf("\n%d passed, %d failed\n", g_pass, g_fail);
    ess_present(0);
    return g_fail ? 1 : 0;
}
