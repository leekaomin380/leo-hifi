/* Candidate D (ES9018 filter on HiFi entry) scenarios.  Runs every earlier
 * suite first.  Host mock only: proves controller logic, not device behaviour.
 * D1 (deep-buffer period), D4 (SIGPIPE) and the capture tap live in
 * audio_hw.c, which this harness does not build; they are verified on device. */
#include "test_candidate_c_lib.c" /* run script renames its main() */

static void card_with_filter(const char *shape)
{
    card_default();
    mock_add_enum("Filter Shape", shape);
}

int main(void)
{
    int writes;
    if (candidate_c_main() != 0)
        return 1;

    printf("== D3a default: entry writes Fast Rolloff ==\n");
    card_with_filter("Minimum Phase"); init_enabled();
    route_active();
    ok("active", lh.effective == LEO_HIFI_ACTIVE);
    ok("filter now Fast Rolloff", strcmp(mock_get_enum("Filter Shape"), "Fast Rolloff") == 0);
    ok("no failure recorded", lh.fail_code == LEO_FAIL_NONE);

    printf("== D3b property selects another valid shape ==\n");
    card_with_filter("Minimum Phase"); mock_prop_put(LEO_PROP_FILTER, "Slow Rolloff"); init_enabled();
    route_active();
    ok("filter now Slow Rolloff", strcmp(mock_get_enum("Filter Shape"), "Slow Rolloff") == 0);

    printf("== D3c keep / invalid values write nothing ==\n");
    card_with_filter("Minimum Phase"); mock_prop_put(LEO_PROP_FILTER, "keep"); init_enabled();
    route_active();
    ok("keep: unchanged", strcmp(mock_get_enum("Filter Shape"), "Minimum Phase") == 0);
    ok("keep: active", lh.effective == LEO_HIFI_ACTIVE);
    card_with_filter("Minimum Phase"); mock_prop_put(LEO_PROP_FILTER, "Bogus Shape"); init_enabled();
    route_active();
    ok("invalid: unchanged", strcmp(mock_get_enum("Filter Shape"), "Minimum Phase") == 0);

    printf("== D3d already correct: no write ==\n");
    card_with_filter("Fast Rolloff"); init_enabled();
    apply_hifi_path();
    writes = mock_write_count;
    leo_hifi_on_route(&lh, true);
    ok("active", lh.effective == LEO_HIFI_ACTIVE);
    ok("filter untouched", strcmp(mock_get_enum("Filter Shape"), "Fast Rolloff") == 0);
    {
        /* Same entry without the filter control present must issue the same
         * number of writes: the filter adds none when it already matches. */
        int with_filter = mock_write_count - writes;
        card_default(); init_enabled();
        apply_hifi_path();
        writes = mock_write_count;
        leo_hifi_on_route(&lh, true);
        ok("matching filter adds zero writes", with_filter == mock_write_count - writes);
    }

    printf("== D3e write failure is advisory ==\n");
    card_with_filter("Minimum Phase"); init_enabled();
    mock_fail_write("Filter Shape", 1);
    route_active();
    ok("still active", lh.effective == LEO_HIFI_ACTIVE);
    ok("fail_code untouched", lh.fail_code == LEO_FAIL_NONE);
    ok("filter unchanged", strcmp(mock_get_enum("Filter Shape"), "Minimum Phase") == 0);

    printf("== D3f control absent (older card) ==\n");
    card_default(); init_enabled();
    route_active();
    ok("active without Filter Shape", lh.effective == LEO_HIFI_ACTIVE);
    ok("ctl_filter NULL", lh.ctl_filter == NULL);

    printf("== D3g fallback paths never write the filter ==\n");
    card_with_filter("Minimum Phase"); init_enabled();
    leo_hifi_on_route(&lh, false);
    ok("wired route: filter unchanged", strcmp(mock_get_enum("Filter Shape"), "Minimum Phase") == 0);

    printf("\n%d passed, %d failed (cumulative, incl. candidate C)\n", g_pass, g_fail);
    return g_fail ? 1 : 0;
}
