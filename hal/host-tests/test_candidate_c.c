/* Candidate C (DAC hard volume) scenarios.  Runs every earlier suite first.
 * Host mock only: proves controller logic, not device behaviour. */
#include "test_candidate_b_lib.c" /* run script renames its main() */

static bool status_has_c(const char *needle)
{
    char status[512];
    leo_hifi_status_string(&lh, status, sizeof(status));
    return strstr(status, needle) != NULL;
}

static void route_active(void)
{
    apply_hifi_path();
    leo_hifi_on_route(&lh, true);
}

int main(void)
{
    char sess[32];
    if (candidate_b_main() != 0)
        return 1;

    printf("== C1 saved DAC level survives a restart, clamped to the cap ==\n");
    card_default(); init_enabled();
    ok("default level is the old fixed -25 dB", lh.vol_user == LEO_HIFI_VOL_DEFAULT);
    card_default(); mock_prop_put(LEO_PROP_VOLUME, "20"); init_enabled();
    ok("persisted level restored", lh.vol_user == 20);
    card_default(); mock_prop_put(LEO_PROP_VOLUME, "99"); init_enabled();
    ok("out-of-range level clamped to cap", lh.vol_user == LEO_HIFI_VOL_MAX);
    ok("init never replays gain, DAC at floor", mock_get_int("Volume", 0) == LEO_HIFI_CTL_FLOOR);

    printf("== C2 verified HiFi entry applies the saved level ==\n");
    card_default(); mock_prop_put(LEO_PROP_VOLUME, "20"); init_enabled();
    route_active();
    ok("active", lh.effective == LEO_HIFI_ACTIVE);
    ok("DAC at user 20 (ctl 175, -40 dB)", mock_get_int("Volume", 0) == 175 && mock_get_int("Volume", 1) == 175);
    ok("status reports -40.0 dB", status_has_c("vol_db:-40.0"));
    ok("never above the cap", mock_get_int("Volume", 0) <= LEO_HIFI_CTL_CEIL);

    printf("== C3 last route exit restores the fail-quiet floor ==\n");
    mock_set_int("QUAT_MI2S_RX Audio Mixer MultiMedia1", 0, 0);
    leo_hifi_on_route_off(&lh);
    ok("DAC back at -60 dB", mock_get_int("Volume", 0) == LEO_HIFI_CTL_FLOOR);
    route_active();
    ok("re-entry replays the level again", mock_get_int("Volume", 0) == 175);

    printf("== C4 a failed replay never leaves a route active ==\n");
    card_default(); mock_prop_put(LEO_PROP_VOLUME, "30"); init_enabled();
    mock_skew_readback("Volume", +3);
    route_active();
    ok("not active after unproven gain", lh.effective != LEO_HIFI_ACTIVE);
    mock_skew_readback("Volume", 0);

    printf("== C5 a level set without a route is stored, then applied ==\n");
    card_default(); init_enabled();
    snprintf(sess, sizeof(sess), "%llu", lh.session);
    {
        char gen[32]; bool changed = false; int writes = mock_write_count;
        snprintf(gen, sizeof(gen), "%llu", lh.generation);
        ok("idle request accepted", leo_hifi_process_request(&lh, NULL, "40", sess, gen, &changed) == 0);
        ok("idle request writes no mixer control", mock_write_count == writes && lh.vol_user == 40);
        ok("idle request persisted", strcmp(mock_prop_get(LEO_PROP_VOLUME), "40") == 0);
    }
    route_active();
    ok("stored level applied on entry (ctl 215, -20 dB)", mock_get_int("Volume", 0) == 215);

    printf("== C6 hard-volume guard arming and disarming ==\n");
    card_default(); init_enabled();
    snprintf(sess, sizeof(sess), "%llu", lh.session);
    ok("arming without session refused", leo_hifi_set_hardvol(&lh, "1", NULL) == -EINVAL && !lh.hardvol);
    ok("arming with stale session refused", leo_hifi_set_hardvol(&lh, "1", "1") == -EAGAIN && !lh.hardvol);
    ok("bad value refused", leo_hifi_set_hardvol(&lh, "2", sess) == -EINVAL);
    ok("arming accepted", leo_hifi_set_hardvol(&lh, "1", sess) == 0 && lh.hardvol);
    ok("armed state persisted", strcmp(mock_prop_get(LEO_PROP_HARDVOL), "1") == 0);
    ok("status reports hardvol:1", status_has_c(",hardvol:1"));
    test_init(LEO_HIFI_DEVICE_NAME);
    ok("armed state survives a restart", lh.hardvol);
    ok("disarming needs no session", leo_hifi_set_hardvol(&lh, "0", NULL) == 0 && !lh.hardvol);
    ok("disarmed state persisted", strcmp(mock_prop_get(LEO_PROP_HARDVOL), "0") == 0);
    lh.supported = false;
    snprintf(sess, sizeof(sess), "%llu", lh.session);
    ok("unsupported controller cannot arm", leo_hifi_set_hardvol(&lh, "1", sess) == -EAGAIN);

    printf("\n%d passed, %d failed\n", g_pass, g_fail);
    return g_fail ? 1 : 0;
}
