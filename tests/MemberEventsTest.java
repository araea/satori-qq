package com.satori.qq.core;

import org.json.JSONObject;

/** 即时灰条与慢成员列表回调必须共用成员事件去重。 */
public final class MemberEventsTest {
    public static void main(String[] args) throws Exception {
        Events events = new Events(null, null, null, null, null);
        JSONObject joined = Notices.groupIncrease(1, 100, 280183116L, 123456, 0);
        check(events.firstMemberChange(joined), "first immediate join");
        check(!events.firstMemberChange(joined), "slow member-list duplicate is suppressed");
        check(!events.firstMemberChange(Notices.groupIncrease(1, 101, 280183116L, 123456, 0)),
                "a separate callback object is still the same join");

        JSONObject removed = Notices.groupDecrease(1, 102, 280183116L, 123456, 0, false);
        check(events.firstMemberChange(removed), "leave changes member state");
        check(!events.firstMemberChange(removed), "duplicate leave is suppressed");
        check(events.firstMemberChange(Notices.groupIncrease(1, 103, 280183116L, 123456, 0)),
                "rejoin after leave is a new event");
        check(events.firstMemberChange(Notices.groupIncrease(1, 103, 280183116L, 654321, 0)),
                "another member is independent");
        System.out.println("MemberEventsTest OK");
    }

    private static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
    }
}
