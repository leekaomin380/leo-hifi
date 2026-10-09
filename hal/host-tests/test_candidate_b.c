/* Candidate B (fixed-44.1 prototype) backend-rate scenarios.
 * Includes the full legacy + lifecycle suites first, so a green run means
 * every earlier assertion still holds. Host mock only, not device evidence. */
#include "test_lifecycle_lib.c" /* run script renames its main() to lifecycle_main() */

static bool rate_is(const char *want)
{
    const char *got = mock_get_enum("QUAT_MI2S SampleRate");
    return got && strcmp(got, want) == 0;
}

static bool status_has(const char *needle)
{
    char status[512];
    leo_hifi_status_string(&lh, status, sizeof(status));
    return strstr(status, needle) != NULL;
}

int main(void)
{
    int writes;
    if (lifecycle_main() != 0)
        return 1;

    printf("== B1 idle backend follows a 44.1 kHz first stream ==\n");
    card_default(); init_enabled();
    leo_hifi_set_entry_rate(&lh, 44100);
    ok("idle prepare writes 44.1", leo_hifi_prepare_backend(&lh, false) == 0 && rate_is("KHZ_44P1"));
    ok("verified rate recorded", strcmp(lh.backend_rate_str, "KHZ_44P1") == 0);
    leo_hifi_set_entry_rate(&lh, 0);
    ok("music joins at 44.1", join(10, 1, 100, true) == 0);
    unsigned long long token = leo_hifi_flow_token_for_stream(&lh, 100);
    leo_hifi_note_frames(&lh, token, 100, 1024, 1024, 512, true);
    leo_hifi_note_frames(&lh, token, 100, 1536, 1024, 512, true);
    leo_hifi_note_frames(&lh, token, 100, 2048, 1024, 512, true);
    ok("status reports 44.1 backend", status_has("backend:S24_LE/KHZ_44P1"));
    ok("44.1 music is live with flow", status_has("live:1,flow:1,"));

    printf("== B2 a live 44.1 backend is never re-clocked ==\n");
    writes = mock_write_count;
    ok("second device reuses 44.1 without writes",
       leo_hifi_prepare_backend(&lh, true) == 0 && mock_write_count == writes && rate_is("KHZ_44P1"));
    ok("48k notification joins without writes", join(11, 5, 200, false) == 0 && mock_write_count == writes);
    ok("notification leaves, music stays 44.1", leave(11, 5) == 0 && rate_is("KHZ_44P1"));
    ok("music still live after notification", status_has("live:1,flow:1,"));
    mock_set_enum("QUAT_MI2S SampleRate", "KHZ_48");
    ok("external drift to 48 is refused, not repaired",
       leo_hifi_route_prepare(&lh, 12, 300) == -EIO && mock_write_count == writes);
    ok("drifted backend is not reported as valid", !status_has("backend:S24_LE/KHZ_48"));
    mock_set_enum("QUAT_MI2S SampleRate", "KHZ_44P1");

    printf("== B3 restore always returns to 48 kHz ==\n");
    ok("music closes", leave(10, 1) == 0);
    leo_hifi_set_entry_rate(&lh, 44100); /* a stale entry must not leak into restore */
    ok("last close restores 48", leo_hifi_finish_backend(&lh, false) == 0 && rate_is("KHZ_48"));
    ok("verified rate back to 48", strcmp(lh.backend_rate_str, "KHZ_48") == 0);
    leo_hifi_set_entry_rate(&lh, 0);

    printf("== B4 notification first keeps 48 for the session ==\n");
    fresh();
    ok("48k first stream keeps 48", rate_is("KHZ_48"));
    ok("notification joins", join(11, 5, 200, false) == 0);
    writes = mock_write_count;
    leo_hifi_set_entry_rate(&lh, 44100);
    ok("later 44.1 music cannot re-clock a live backend",
       leo_hifi_prepare_backend(&lh, true) == 0 && mock_write_count == writes && rate_is("KHZ_48"));
    leo_hifi_set_entry_rate(&lh, 0);
    ok("music joins at 48 backend", join(10, 1, 100, true) == 0 && status_has("backend:S24_LE/KHZ_48"));

    printf("== B5 unsupported rates fall back to 48 ==\n");
    card_default(); init_enabled();
    leo_hifi_set_entry_rate(&lh, 96000);
    ok("96k entry selects 48", leo_hifi_prepare_backend(&lh, false) == 0 && rate_is("KHZ_48"));
    card_default(); init_enabled();
    leo_hifi_set_entry_rate(&lh, 22050);
    ok("22.05k entry selects 48", leo_hifi_prepare_backend(&lh, false) == 0 && rate_is("KHZ_48"));
    leo_hifi_set_entry_rate(&lh, 0);

    printf("== B6 failed 44.1 write is never acknowledged ==\n");
    card_default(); init_enabled();
    leo_hifi_set_entry_rate(&lh, 44100);
    mock_fail_write("QUAT_MI2S SampleRate", 1);
    ok("write failure reported", leo_hifi_prepare_backend(&lh, false) != 0 && lh.backend_config_failed);
    ok("failed write keeps previous verified rate", strcmp(lh.backend_rate_str, "KHZ_48") == 0);
    ok("route confirmation refuses failed backend", leo_hifi_route_prepare(&lh, 10, 100) == -EIO);
    mock_fail_write("QUAT_MI2S SampleRate", 0);
    card_default(); init_enabled();
    leo_hifi_set_entry_rate(&lh, 44100); /* init_enabled() resets the controller */
    mock_enum_readback("QUAT_MI2S SampleRate", "KHZ_48");
    ok("44.1 read-back mismatch fails", leo_hifi_prepare_backend(&lh, false) != 0 &&
       lh.fail_code == LEO_FAIL_BACKEND_READBACK);
    leo_hifi_set_entry_rate(&lh, 0);

    printf("\n%d passed, %d failed\n", g_pass, g_fail);
    return g_fail ? 1 : 0;
}
