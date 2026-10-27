package com.satori.qq.core;

import com.satori.qq.qq.ExtraSvc;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 能力目录：QQ 端多接一个动作，客户端当天就能用上的那份清单。
 *
 * <p>这条测试守的是「目录与实现不许分家」：每个 {@link ExtraSvc} 动作都要出现，每条目录
 * 记录都要能被 {@link Capabilities#declares} 认下，分类只能是读、写、管理三档，名字不许
 * 重复。少一条，客户端就看不见那个按钮；多一条对不上的，模型会照着调一个必然 404 的动作。
 */
public final class CapabilitiesTest {
    public static void main(String[] args) throws Exception {
        coversEveryKernelAction();
        entriesAreWellFormed();
        removedActionsAreNotDeclared();
        System.out.println("CapabilitiesTest OK");
    }

    private static void coversEveryKernelAction() throws Exception {
        JSONArray catalog = catalog();
        Set<String> names = new HashSet<>();
        for (int i = 0; i < catalog.length(); i++) names.add(catalog.getJSONObject(i).getString("name"));
        for (ExtraSvc.Spec spec : ExtraSvc.actions()) {
            check(names.contains(spec.name), "目录里少了内核动作 " + spec.name);
        }
        // 标准方法与核心内部动作同样要在里面。
        for (String name : new String[]{"message.create", "guild.member.list", "poke", "essence", "capabilities"}) {
            check(names.contains(name), "目录里少了 " + name);
        }
    }

    private static void entriesAreWellFormed() throws Exception {
        JSONArray catalog = catalog();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < catalog.length(); i++) {
            JSONObject entry = catalog.getJSONObject(i);
            String name = entry.getString("name");
            check(!name.isEmpty(), "名字不能为空");
            check(seen.add(name), "名字重复：" + name);
            String kind = entry.getString("kind");
            check(Set.of("read", "write", "manage").contains(kind), name + " 的分类不合法：" + kind);
            check(entry.has("params"), name + " 缺参数说明");
            check(entry.has("mutating"), name + " 缺 mutating");
            check(entry.has("aliases") && entry.get("aliases") instanceof JSONArray, name + " 的 aliases 不是数组");
            // 目录里的每一条都必须是 declares 认得的：否则信封会先通过检查、然后在分发时 404。
            check(Capabilities.declares(name), "declares 不认识目录里的 " + name);
            // 写语义与 kind 要自洽：读动作不该被标成会改动。
            check(kind.equals("read") != entry.getBoolean("mutating"), name + " 的 kind 与 mutating 矛盾");
        }
        // 管理动作至少有几个：踢、禁言这些走标准方法，扩展里也有。
        boolean anyManage = false;
        for (int i = 0; i < catalog.length(); i++) {
            if ("manage".equals(catalog.getJSONObject(i).getString("kind"))) anyManage = true;
        }
        check(anyManage, "目录里一条管理动作都没有，多半是分类逻辑坏了");
    }

    private static void removedActionsAreNotDeclared() {
        // 移除过的动作既不该在目录里，也不该被 declares 认下——它有自己的错误码。
        check(Capabilities.removedSince("like") == null, "like 0.53.0 起回来了，不该再记成移除");
        check(Capabilities.removedSince("offline") != null, "offline 应该记着是哪个版本移除的");
        check(!Capabilities.declares("offline"), "移除过的 offline 不该还能调");
        for (String name : new String[]{"like", "group_kick", "group_mute", "group_transfer",
                "group_settings", "group_detail", "group_msg_mask", "session_top", "profile_set",
                "online_status", "user_info", "friend_block", "group_destroy"}) {
            check(Capabilities.declares(name), name + " 应该在能力目录里");
            check(Capabilities.removedSince(name) == null, name + " 不能同时记成移除");
        }
    }

    private static JSONArray catalog() throws Exception {
        return Capabilities.build().getJSONArray("catalog");
    }

    private static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
    }
}
