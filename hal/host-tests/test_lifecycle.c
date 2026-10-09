#define main legacy_main
#include "test_leo_hifi.c"
#undef main

static void fresh(void)
{
    card_default(); init_enabled();
    ok("prepare idle backend", leo_hifi_prepare_backend(&lh, false) == 0);
}

static int join(int id, int mm, uintptr_t stream, bool music)
{
    char name[64];
    snprintf(name, sizeof(name), "QUAT_MI2S_RX Audio Mixer MultiMedia%d", mm);
    mock_set_int(name, 1, 0);
    return leo_hifi_route_enable(&lh, id, mm, stream, music, true);
}

static int leave(int id, int mm)
{
    char name[64];
    snprintf(name, sizeof(name), "QUAT_MI2S_RX Audio Mixer MultiMedia%d", mm);
    mock_set_int(name, 0, 0);
    return leo_hifi_route_disable(&lh, id);
}

int main(void)
{
    legacy_main();
    printf("== Lifecycle: music plus notification, repeated route hooks ==\n");
    fresh();
    ok("music joins", join(10, 1, 100, true) == 0);
    unsigned long long token = leo_hifi_flow_token_for_stream(&lh, 100);
    leo_hifi_note_frames(&lh, token, 100, 1024, 1024, 512, true);
    leo_hifi_note_frames(&lh, token, 100, 1536, 1024, 512, true);
    leo_hifi_note_frames(&lh, token, 100, 2048, 1024, 512, true);
    int writes = mock_write_count;
    unsigned long long generation = lh.generation;
    ok("second device reuses backend without writes", leo_hifi_prepare_backend(&lh, true) == 0 && mock_write_count == writes);
    ok("notification joins", join(11, 5, 200, false) == 0);
    ok("join preserves music token and generation", leo_hifi_flow_token_for_stream(&lh,100) == token && lh.generation == generation);
    ok("duplicate join is idempotent", join(11, 5, 200, false) == 0 && lh.route_count == 2);
    ok("conflicting owner refused", leo_hifi_route_prepare(&lh, 11, 201) == -EEXIST);
    ok("notification has no music token", leo_hifi_flow_token_for_stream(&lh, 200) == 0);
    ok("notification leaves", leave(11, 5) == 0);
    ok("duplicate close is idempotent", leo_hifi_route_disable(&lh, 11) == 0 && lh.route_count == 1);
    ok("surviving music blocks restoration", leo_hifi_finish_backend(&lh, false) == -EBUSY && mock_write_count == writes);
    char status[512]; leo_hifi_status_string(&lh,status,sizeof(status));
    ok("music remains live after notification close", strstr(status,"live:1,flow:1,") != NULL);
    ok("music closes", leave(10, 1) == 0);
    ok("remaining device blocks restoration", leo_hifi_finish_backend(&lh,true) == -EBUSY && mock_write_count == writes);
    ok("last close restores backend", leo_hifi_finish_backend(&lh,false) == 0 && !lh.backend_restore_pending && mock_write_count > writes);
    writes = mock_write_count;
    ok("repeated restoration makes no writes", leo_hifi_finish_backend(&lh,false) == 0 && mock_write_count == writes);
    ok("old music token invalidated", leo_hifi_flow_token_for_stream(&lh,100) == 0 && leo_hifi_flow_token(&lh) != token);

    printf("== Lifecycle: failed entry/close and active backend drift ==\n");
    fresh(); ok("music joins", join(10,1,100,true) == 0);
    writes=mock_write_count; generation=lh.generation;
    ok("failed FE entry cannot borrow MM1", leo_hifi_route_enable(&lh,11,5,200,false,true) == -EIO && lh.route_count == 1);
    ok("failed entry preserves music generation", lh.generation == generation && mock_write_count == writes);
    ok("unproven close retains ownership", leo_hifi_route_disable(&lh,10) == -EIO && lh.route_count == 1);
    mock_set_enum("QUAT_MI2S SampleRate","KHZ_96");
    ok("active drift refused without repair", leo_hifi_prepare_backend(&lh,true) == -EBUSY && mock_write_count == writes);
    ok("confirmation reports drift without writes", leo_hifi_route_prepare(&lh,12,300) == -EIO && mock_write_count == writes);
    mock_set_enum("QUAT_MI2S SampleRate","KHZ_48");
    ok("music closes", leave(10,1) == 0);
    mock_set_int("QUAT_MI2S_RX Audio Mixer MultiMedia2",1,0);
    ok("unowned frontend blocks clock restoration", leo_hifi_finish_backend(&lh,false) == -EBUSY && mock_write_count == writes);
    mock_set_int("QUAT_MI2S_RX Audio Mixer MultiMedia2",-1,0);
    ok("unreadable frontend also blocks restoration", leo_hifi_finish_backend(&lh,false) == -EBUSY && mock_write_count == writes);
    mock_set_int("QUAT_MI2S_RX Audio Mixer MultiMedia2",0,0);
    mock_add_int("QUAT_MI2S_RX_Voice Mixer Voice Stub",1,1,0);
    ok("voice connection blocks restoration", leo_hifi_finish_backend(&lh,false) == -EBUSY && mock_write_count == writes);
    mock_set_int("QUAT_MI2S_RX_Voice Mixer Voice Stub",0,0);
    mock_fail_write("QUAT_MI2S SampleRate",1);
    ok("restore failure remains pending", leo_hifi_finish_backend(&lh,false) != 0 && lh.backend_restore_pending);
    mock_fail_write("QUAT_MI2S SampleRate",0);
    ok("idle retry completes restoration", leo_hifi_finish_backend(&lh,false) == 0 && !lh.backend_restore_pending);

    printf("== Lifecycle: closing music before notification ==\n");
    fresh(); join(10,1,100,true); join(11,5,200,false);
    writes=mock_write_count;
    ok("music closes before notification", leave(10,1) == 0 && lh.route_count == 1 && lh.flow_owner == 0);
    ok("notification still prevents clock writes", leo_hifi_finish_backend(&lh,false) == -EBUSY && mock_write_count == writes);
    leave(11,5);
    ok("final notification close permits restore", leo_hifi_finish_backend(&lh,false) == 0);
    printf("\n%d passed, %d failed\n",g_pass,g_fail);
    return g_fail ? 1 : 0;
}
