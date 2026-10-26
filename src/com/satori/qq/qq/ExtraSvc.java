package com.satori.qq.qq;

import android.os.Handler;
import android.os.Looper;
import com.satori.qq.L;
import com.satori.qq.core.ApiError;
import com.satori.qq.packet.PacketSvc;
import com.satori.qq.packet.Pb;
import com.satori.qq.satori.Codec;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Thin wrappers over the QQNT kernel services that the Satori core does not need but the
 * module exposes as QQ-only extensions: profile self-service, group settings, buddy remarks
 * and session flags, and the recent-contact snapshot.
 *
 * <p>Every kernel call here is asynchronous and reports back through a three-argument
 * {@code onResult(int code, String msg, Object payload)}. {@link #call} performs that wait
 * and keeps the payload, so callers can either surface it verbatim or pick fields out of it.
 * The services themselves are obtained from the already-captured session in {@link QQClient};
 * this class never hooks anything.</p>
 */
public final class ExtraSvc {
    private static final String NS = "com.tencent.qqnt.kernel.nativeinterface.";
    private static final String OPERATE_CB =
            "com.tencent.qqnt.kernel.nativeinterface.IOperateCallback";
    private static final String GROUP_INFO_SOURCE =
            "com.tencent.qqnt.kernel.nativeinterface.GroupInfoSource";
    private static final String GROUP_ESSENCE_CB =
            "com.tencent.qqnt.kernel.nativeinterface.IFetchGroupEssenceListCallback";
    private static final String IDENTITY_LIST_CB =
            "com.tencent.qqnt.kernel.nativeinterface.IGetIdentityListCallback";
    private static final String DETAIL_BY_UIN_CB =
            "com.tencent.qqnt.kernel.nativeinterface.IDetailInfoByUinCallback";
    // guestA2ToPskey 要的是 IGuestPskeyCallback（onResult(int, String, ArrayList<GuestPskey>)）。
    // 拿 IGetPskeyCallback 去接会让内核按错误的接口回调，段错误直接把 QQ 带走。
    private static final String GUEST_PSKEY_CB =
            "com.tencent.qqnt.kernel.nativeinterface.IGuestPskeyCallback";

    // --- 0.8.9.39: kernel types used by the extended group/message/media/profile actions ---
    private static final String JOIN_LINK_CB =
            "com.tencent.qqnt.kernel.nativeinterface.IGetJoinGroupLinkCallback";
    private static final String MEMBER_CARD_CB =
            "com.tencent.qqnt.kernel.nativeinterface.IGetGroupMemberCardInfoCallback";
    private static final String MSG_OPERATE_CB =
            "com.tencent.qqnt.kernel.nativeinterface.IMsgOperateCallback";
    private static final String DRAFT_CB =
            "com.tencent.qqnt.kernel.nativeinterface.IGetDraftOperateCallback";
    private static final String TEMP_CHAT_CB =
            "com.tencent.qqnt.kernel.nativeinterface.IGetTempChatInfoCallback";
    private static final String MSG_ABSTRACT_CB =
            "com.tencent.qqnt.kernel.nativeinterface.IGetMsgAbstractsCallback";
    private static final String BATCH_FILE_COUNT_CB =
            "com.tencent.qqnt.kernel.nativeinterface.IBatchGroupFileCountCallback";

    private static final long TIMEOUT_MS = 15_000L;

    /** Outcome of one kernel call: transport-level code plus the callback's payload. */
    public static final class Result {
        public int code = -1;
        public String msg = "";
        public boolean timedOut;
        public Object payload;

        public boolean ok() { return !timedOut && code == 0; }

        public String describe() {
            if (timedOut) return msg == null || msg.isEmpty() ? "timeout" : msg;
            if (msg == null || msg.isEmpty()) return "code=" + code;
            return "code=" + code + " " + msg;
        }
    }

    /** One kernel invocation: hand the service and a callback proxy to {@code run}. */
    public interface Call {
        void run(Object svc, Object cb) throws Exception;
    }

    private final QQClient qq;
    private final Ref ref;

    public ExtraSvc(QQClient qq) {
        this.qq = qq;
        this.ref = qq.ref;
    }

    /**
     * Invoke {@code call} and wait for {@code onResult}. The payload (third argument) is kept
     * even when the code is non-zero, so diagnostics can show what QQ returned.
     */
    public Result call(Object svc, String cbClass, String label, Call call) {
        return call(svc, cbClass, label, call, TIMEOUT_MS);
    }

    /** 同样的等待，但给交互类动作一个更短的时限：群头衔这类动作不该让 HTTP 线程卡 15 秒。 */
    public Result call(Object svc, String cbClass, String label, Call call, long timeoutMs) {
        Result r = new Result();
        if (svc == null) {
            r.msg = label + ": service not ready";
            Compat.observe(label, "failed");
            return r;
        }
        try {
            final CountDownLatch latch = new CountDownLatch(1);
            final AtomicInteger code = new AtomicInteger(-1);
            final String[] wording = new String[]{""};
            final Object[] payload = new Object[]{null};
            Object cb = Proxy.newProxyInstance(ref.cl, new Class[]{ref.cls(cbClass)}, (p, m, args) -> {
                // Kernel callbacks come in two shapes. Most report `(int code, String msg,
                // <payload>...)`; a few carry the payload alone, e.g.
                // `IBatchQueryCachedGroupDetailInfoCallback.onResult(ArrayList<GroupDetailInfo>)`.
                // Both have to complete the wait, or the second shape silently times out.
                //
                // Only methods named `on*` count as callbacks: `Object.equals` hands the proxy a
                // single non-numeric argument too, and treating that as a payload would let a
                // bookkeeping call complete the wait with the wrong value.
                String cbName = m.getName();
                boolean isCallback = cbName != null && cbName.startsWith("on");
                if (isCallback && args != null && args.length >= 2 && args[0] instanceof Number) {
                    code.set(Ref.asInt(args[0]));
                    wording[0] = Ref.asStr(args[1]);
                    if (args.length >= 3 && args[2] != null) payload[0] = args[2];
                    latch.countDown();
                } else if (isCallback && args != null && args.length == 1 && args[0] != null
                        && !(args[0] instanceof Number)) {
                    code.set(0);
                    wording[0] = "payload";
                    payload[0] = args[0];
                    latch.countDown();
                } else {
                    L.i("ExtraSvc " + label + " cb." + cbName + " args="
                            + (args == null ? 0 : args.length));
                }
                return defOf(m.getReturnType());
            });
            L.i("ExtraSvc invoke " + label + " on " + svc.getClass().getName());
            // QQ's kernel services are main-thread affine: invoking them from an HTTP worker
            // thread returns immediately but the callback never fires. Post the invocation to the
            // main looper (which returns at once) and keep waiting here on the worker thread.
            Looper main = Looper.getMainLooper();
            if (main != null && Looper.myLooper() != main) {
                new Handler(main).post(() -> {
                    try {
                        call.run(svc, cb);
                        L.i("ExtraSvc dispatched " + label);
                    } catch (Throwable t) {
                        L.e("ExtraSvc " + label + " dispatch", t);
                        latch.countDown();
                    }
                });
            } else {
                call.run(svc, cb);
                L.i("ExtraSvc dispatched " + label);
            }
            if (!latch.await(Math.max(1_000L, timeoutMs), TimeUnit.MILLISECONDS)) {
                r.timedOut = true;
                r.msg = label + " timeout";
                Compat.observe(label, "timeout");
                L.i("ExtraSvc " + label + " -> timeout");
                return r;
            }
            r.code = code.get();
            r.msg = wording[0] == null ? "" : wording[0];
            r.payload = payload[0];
            Compat.observe(label, r.ok() ? "ok" : "failed");
            L.i("ExtraSvc " + label + " -> " + r.describe()
                    + (r.payload == null ? "" : " payload=" + r.payload.getClass().getName()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            r.msg = label + " interrupted";
        } catch (Throwable t) {
            L.e(label, t);
            r.msg = String.valueOf(t);
            Compat.observe(label, "failed");
        }
        return r;
    }

    private static Object defOf(Class<?> r) {
        if (!r.isPrimitive()) return null;
        if (r == boolean.class) return false;
        if (r == void.class) return null;
        if (r == long.class) return 0L;
        if (r == int.class) return 0;
        if (r == double.class) return 0d;
        if (r == float.class) return 0f;
        if (r == short.class) return (short) 0;
        if (r == byte.class) return (byte) 0;
        if (r == char.class) return (char) 0;
        return null;
    }

    /**
     * Whether a callback payload actually carries something. An empty list or map is what a kernel
     * cache answers when it has nothing — treating that as data would stop the caller from trying
     * the next entry.
     */
    private static boolean hasData(Object payload) {
        if (payload == null) return false;
        if (payload instanceof Collection<?> items) return !items.isEmpty();
        if (payload instanceof Map<?, ?> entries) return !entries.isEmpty();
        if (payload instanceof String text) return !text.trim().isEmpty();
        return true;
    }

    private String selfUid() {
        try {
            return qq.resolveUid(parseUin(qq.selfUin()));
        } catch (Throwable t) {
            return "";
        }
    }

    private static long parseUin(String uin) {
        if (uin == null) return 0;
        try { return Long.parseLong(uin.trim()); } catch (NumberFormatException e) { return 0; }
    }

    // ---------------------------------------------------------------- profile

    // --------------------------------------------------------- group settings

    // ------------------------------------------------------------------ buddy


    // --------------------------------------------------------------- recent

    // ------------------------------------------------------------ group reads


    /**
     * The enum constant is passed straight to native code, so a null here silently returns
     * nothing. Probe the spellings this client knows instead of trusting one.
     */
    private Object groupInfoSource(String name) {
        String[] candidates = name == null || name.trim().isEmpty()
                ? new String[]{"KDATACARD", "KAIO", "KUNSPECIFIED"}
                : new String[]{name.trim(), "KDATACARD", "KUNSPECIFIED"};
        for (String candidate : candidates) {
            try {
                Object value = ref.getStatic(GROUP_INFO_SOURCE, candidate);
                if (value != null) return value;
            } catch (Throwable ignore) {}
        }
        return null;
    }


    // ------------------------------------------------------------------ buddy


    // ---------------------------------------------------------------- profile


    // ----------------------------------------------------------------- message

    // -------------------------------------------------------------- rich media

    // ------------------------------------------------------- group administration

    // ------------------------------------------------------------------ message

    /** The conversation's draft. */
    public Result draft(Object contact) {
        return call(qq.getMsgService(), DRAFT_CB, "getDraft",
                (svc, cb) -> ref.call(svc, "getDraft", contact, cb));
    }

    // --------------------------------------------------------- recent contacts

    // ------------------------------------------------------------------- robot

    // ------------------------------------------------------------ group identity

    /** 群主 / 管理员身份头衔的内置 id（QQ 的群身份目录固定给这两枚）。 */
    static final int TITLE_OWNER = 300;
    static final int TITLE_ADMIN = 301;
    /** 群等级头衔的内置 id：同一 id 在不同群里显示不同的等级名（青铜、彗星……）。 */
    static final int TITLE_LEVEL = 315;
    /** 群主授予的专属头衔：目录里固定是 302，名字就是那段自定义文字。 */
    static final int TITLE_CUSTOM = 302;

    /** 读一次内核 getIdentityList：群头衔、互动标签、群等级都在里面。 */
    public ExtraSvc.Result identityList(long groupCode, long memberUin) {
        return identityList(groupCode, memberUin, TIMEOUT_MS);
    }

    public ExtraSvc.Result identityList(long groupCode, long memberUin, long timeoutMs) {
        if (groupCode == 0) throw new IllegalArgumentException("missing guild_id");
        Object req = neu(NS + "GetIdentityListReq");
        put(req, "groupCode", groupCode);
        put(req, "memberUin", memberUin);
        return call(svc("group"), IDENTITY_LIST_CB, "getIdentityList",
                (svc2, cb) -> ref.call(svc2, "getIdentityList", req, true, cb), timeoutMs);
    }

    /** 把 getIdentityList 的回包整理成调用方看得懂的样子。 */
    JSONObject titleListJson(long groupCode, long memberUin) throws Exception {
        ExtraSvc.Result r = identityList(groupCode, memberUin, 8_000L);
        if (!r.ok()) throw ApiError.failed("getIdentityList: " + r.describe());
        JSONArray titles = new JSONArray();
        Object list = r.payload == null ? null : ref.get(r.payload, "titles");
        if (list instanceof Collection<?> items) {
            for (Object t : items) {
                if (t == null) continue;
                int id = intField(ref.get(t, "titleId"));
                String kind = id == TITLE_OWNER || id == TITLE_ADMIN ? "role"
                        : id == TITLE_CUSTOM ? "custom"
                        : id == TITLE_LEVEL ? "level" : "other";
                titles.put(new JSONObject()
                        .put("title_id", id)
                        .put("name", Ref.asStr(ref.get(t, "name")))
                        .put("worn", Ref.asBool(ref.get(t, "isSet")))
                        .put("kind", kind)
                        .put("pop_text", Ref.asStr(ref.get(t, "popText")))
                        .put("spec_value", Ref.asInt(ref.get(t, "specValue"))));
            }
        }
        String role = roleOf(titles);
        int wornId = 0;
        String worn = "";
        for (int i = 0; i < titles.length(); i++) {
            JSONObject t = titles.optJSONObject(i);
            if (t != null && t.optBoolean("worn")) {
                worn = t.optString("name", "");
                wornId = t.optInt("title_id");
                break;
            }
        }
        JSONObject out = new JSONObject()
                .put("guild_id", String.valueOf(groupCode))
                .put("user_id", String.valueOf(memberUin))
                .put("role", role)
                .put("can_set_special", "owner".equals(role))
                .put("can_wear_role", !"member".equals(role))
                .put("role_title_ids", new JSONObject()
                        .put("owner", TITLE_OWNER).put("admin", TITLE_ADMIN)
                        .put("custom", TITLE_CUSTOM).put("level", TITLE_LEVEL))
                .put("titles", titles)
                .put("default_title_id", defaultTitleId(titles, role))
                .put("special_title", customTitle(titles))
                .put("worn", worn)
                .put("worn_title_id", wornId);
        Object level = r.payload == null ? null : ref.get(r.payload, "level");
        if (level != null) {
            out.put("level", new JSONObject()
                    .put("cur", Ref.asInt(ref.get(level, "curLevel")))
                    .put("name", Ref.asStr(ref.get(level, "name")))
                    .put("progress", Ref.asInt(ref.get(level, "progress"))));
        }
        return out;
    }

    /** 300/301 出现时，对应身份真的在这个账号身上；否则就不是靠猜。 */
    private static String roleOf(JSONArray titles) {
        boolean admin = false;
        for (int i = 0; i < titles.length(); i++) {
            JSONObject t = titles.optJSONObject(i);
            if (t == null) continue;
            int id = t.optInt("title_id");
            if (id == TITLE_OWNER) return "owner";
            if (id == TITLE_ADMIN) admin = true;
        }
        return admin ? "admin" : "member";
    }

    /** 群主授予的专属头衔在身份目录里是 302，名字就是那段自定义文字。 */
    private static String customTitle(JSONArray titles) {
        for (int i = 0; i < titles.length(); i++) {
            JSONObject t = titles.optJSONObject(i);
            if (t != null && t.optInt("title_id") == TITLE_CUSTOM) return t.optString("name", "");
        }
        return "";
    }

    private static int defaultTitleId(JSONArray titles, String role) {
        if ("owner".equals(role)) {
            int owner = findTitle(titles, TITLE_OWNER);
            if (owner > 0) return owner;
        }
        if ("admin".equals(role) || "owner".equals(role)) {
            int admin = findTitle(titles, TITLE_ADMIN);
            if (admin > 0) return admin;
        }
        int level = firstKind(titles, "level");
        if (level > 0) return level;
        return firstWearable(titles);
    }

    private static int findTitle(JSONArray titles, int wanted) {
        for (int i = 0; i < titles.length(); i++) {
            JSONObject t = titles.optJSONObject(i);
            if (t != null && t.optInt("title_id") == wanted) return wanted;
        }
        return -1;
    }

    private static int firstKind(JSONArray titles, String kind) {
        for (int i = 0; i < titles.length(); i++) {
            JSONObject t = titles.optJSONObject(i);
            if (t != null && kind.equals(t.optString("kind"))) return t.optInt("title_id");
        }
        return -1;
    }

    private static int firstWearable(JSONArray titles) {
        for (int i = 0; i < titles.length(); i++) {
            JSONObject t = titles.optJSONObject(i);
            if (t == null) continue;
            int id = t.optInt("title_id");
            if (id > 0) return id;
        }
        return -1;
    }

    private static int findTitleByName(JSONArray titles, String wanted) {
        if (wanted == null || wanted.isEmpty()) return -1;
        for (int i = 0; i < titles.length(); i++) {
            JSONObject t = titles.optJSONObject(i);
            if (t != null && wanted.equalsIgnoreCase(t.optString("name", "").trim())) {
                return t.optInt("title_id");
            }
        }
        return -1;
    }

    private static boolean isOff(String value) {
        String v = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return v.equals("off") || v.equals("none") || v.equals("clear") || v.equals("摘")
                || v.equals("取消") || v.equals("下掉") || v.equals("关");
    }

    private static boolean isOwnerRole(String value) {
        String v = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return v.equals("owner") || v.equals("群主") || v.equals("主人");
    }

    private static boolean isAdminRole(String value) {
        String v = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return v.equals("admin") || v.equals("管理员") || v.equals("管理");
    }

    private static boolean isLevelRole(String value) {
        String v = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return v.equals("level") || v.equals("等级") || v.equals("群等级");
    }

    /** 内核把 id 写成 String（"300"）的情况很常见，别直接 Number 强转。 */
    private static int intField(Object value) {
        if (value instanceof Number n) return n.intValue();
        if (value == null) return 0;
        try { return Integer.parseInt(String.valueOf(value).trim()); }
        catch (NumberFormatException e) { return 0; }
    }

    private static JSONObject attempt(String strategy, boolean ok, String msg) throws Exception {
        return new JSONObject().put("strategy", strategy).put("ok", ok).put("msg", msg == null ? "" : msg);
    }

    private static String attemptsText(JSONArray attempts) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < attempts.length(); i++) {
            JSONObject a = attempts.optJSONObject(i);
            if (a == null) continue;
            if (out.length() > 0) out.append("; ");
            out.append(a.optString("strategy", "?"));
            if (a.optBoolean("ok")) out.append(" ok");
            else out.append(" failed ").append(a.optString("msg", ""));
        }
        return out.toString();
    }

    /**
     * 按 role / name / title_id 解析要戴哪一枚头衔。
     *
     * <p>300/301 不是“随便填就能上报”的编号：它们只有真的群主/管理员才会出现在身份目录里。
     * 不在目录里就说明服务器不会接受，返回 -1，由调用方决定是写专属头衔还是把原因说清楚。
     * 返回 -2 表示“摘”：清掉专属头衔，再回到身份目录里的默认头衔。
     */
    private static int resolveTitleId(JSONArray titles, int explicit, String role, String name) {
        if (explicit > 1) return explicit;
        String r = role == null ? "" : role.trim().toLowerCase(Locale.ROOT);
        String wanted = name == null ? "" : name.trim();
        if (isOff(r) || isOff(wanted)) return -2;
        if (isOwnerRole(r) || "群主".equals(wanted)) {
            int id = findTitle(titles, TITLE_OWNER);
            if (id > 0) return id;
            id = findTitleByName(titles, "群主");
            return id > 0 ? id : -1;
        }
        if (isAdminRole(r) || "管理员".equals(wanted)) {
            int id = findTitle(titles, TITLE_ADMIN);
            if (id > 0) return id;
            id = findTitleByName(titles, "管理员");
            return id > 0 ? id : -1;
        }
        if (isLevelRole(r) || "等级".equals(wanted) || "群等级".equals(wanted)) {
            int id = findTitle(titles, TITLE_LEVEL);
            if (id > 0) return id;
            id = firstKind(titles, "level");
            if (id > 0) return id;
            return firstWearable(titles);
        }
        if (!wanted.isEmpty()) {
            return findTitleByName(titles, wanted);
        }
        return defaultTitleId(titles, roleOf(titles));
    }

    private static String titleName(JSONArray titles, int titleId) {
        for (int i = 0; i < titles.length(); i++) {
            JSONObject t = titles.optJSONObject(i);
            if (t != null && t.optInt("title_id") == titleId) return t.optString("name", "");
        }
        return "";
    }

    private static boolean hasTitle(JSONArray titles, int titleId) {
        return titleId > 0 && findTitle(titles, titleId) > 0;
    }

    private static String hintFor(String reason, String role, String display) {
        String label = display == null || display.isEmpty() ? "这枚头衔" : "「" + display + "」";
        return switch (reason) {
            case "owner_required" -> "owner".equals(role)
                    ? "你是群主，但 QQ 这次没有接受" + label + "；检查一下头衔长度（最多六字）或稍后再试。"
                    : "你在这个群是" + ("admin".equals(role) ? "管理员" : "普通成员")
                            + "，QQ 只让群主写专属头衔。想显示" + label
                            + "，让群主用「设置专属头衔」给你写；写好后用 title_list 就能看到并戴上。";
            case "not_available" -> "本群身份目录里没有" + label
                    + "。普通成员只能戴已经获得的身份头衔（群等级、群主授予的专属头衔）；群主 / 管理员身份要真的拥有对应角色。";
            case "kernel_refused" -> "QQ 拒绝了" + label + " 的上报。普通成员戴不上群主 / 管理员身份，换一枚目录里已有的头衔试试。";
            case "special_failed" -> "写专属头衔失败了，QQ 没有接受" + label + "。";
            case "timeout" -> "QQ 内核没有及时回话，稍后再试。";
            default -> "这次没有戴上" + label + "。";
        };
    }

    /** 内核佩戴身份目录里已有的头衔：只发 groupId + titleId。 */
    private ExtraSvc.Result wearByKernel(long groupCode, int titleId) {
        Object req = neu(NS + "SetIdentityTitleInfoReq");
        put(req, "groupId", groupCode);
        put(req, "titleId", titleId);
        return call(svc("group"), OPERATE_CB, "setIdentityTitleInfo",
                (svc2, cb) -> ref.call(svc2, "setIdentityTitleInfo", req, cb), 8_000L);
    }

    /**
     * 写自己的专属头衔（0x8FC_2）。只有群主能做，写成功后身份目录里会多出一枚 302，
     * 名字就是这段文字；写成功后可以在 title_list 里看到并戴上。
     */
    private PacketSvc.Result setSpecialTitle(long groupCode, long targetUin, String title) throws Exception {
        String uid = uidFor(groupCode, targetUin);
        if (uid.isEmpty()) throw ApiError.failed("cannot resolve uid for " + targetUin);
        byte[] member = Pb.w().string(1, uid).string(5, title).varint(6, -1L).string(7, title).toByteArray();
        byte[] body = Pb.w().varint(1, groupCode).varint(2, 1).message(3, member).toByteArray();
        return qq.packets().sendOidb(0x8FC, 2, body, 8_000L);
    }

    private String uidFor(long groupCode, long uin) {
        try {
            String uid = qq.resolveUid(uin);
            if (uid != null && !uid.isEmpty()) return uid;
        } catch (Throwable ignore) {}
        try {
            Map<String, Object> members = qq.getAllMembers(groupCode);
            if (members != null) {
                for (Object member : members.values()) {
                    if (member != null && Ref.asLong(ref.get(member, "uin")) == uin) {
                        return Ref.asStr(ref.get(member, "uid"));
                    }
                }
            }
        } catch (Throwable ignore) {}
        return "";
    }

    private Object titleList(JSONObject p) throws Exception {
        long g = groupOf(p);
        if (g == 0) throw ApiError.badRequest("missing guild_id");
        long u = parseUin(p.optString("user_id", ""));
        if (u == 0) u = parseUin(qq.selfUin());
        return titleListJson(g, u);
    }

    private Object wearTitle(JSONObject p) throws Exception {
        long g = groupOf(p);
        if (g == 0) throw ApiError.badRequest("missing guild_id");
        long self = parseUin(qq.selfUin());
        long target = parseUin(p.optString("user_id", ""));
        if (target == 0) target = self;
        if (target != self) {
            throw ApiError.badRequest("title_wear 只改本登录号自己的头衔；给别人写头衔用 group_title / special_title");
        }
        String role = p.optString("role", "");
        String wantedName = p.optString("name", "").trim();
        String customText = p.optString("title", "").trim();
        int explicit = p.has("title_id") ? p.optInt("title_id", -1) : -1;
        String strategy = p.optString("strategy", "auto").trim().toLowerCase(Locale.ROOT);
        boolean verify = p.optBoolean("verify", true);
        long settle = Math.max(0, Math.min(2000, p.optLong("settle_ms", 350)));
        if (explicit == 0 || explicit == 1) {
            throw ApiError.badRequest("title_id 0/1 是群头衔显示开关，不是可佩戴的头衔；要摘头衔请用 role=off");
        }
        if (customText.codePointCount(0, customText.length()) > 6) {
            customText = customText.substring(0, customText.offsetByCodePoints(0, 6));
        }

        JSONObject before = titleListJson(g, self);
        String selfRole = before.optString("role", "member");
        boolean owner = "owner".equals(selfRole);
        JSONArray titles = before.optJSONArray("titles");
        int titleId = resolveTitleId(titles, explicit, role, wantedName);
        boolean off = titleId == -2 || isOff(role) || isOff(wantedName) || "off".equals(strategy);

        // 想要「群主 / 管理员」但身份目录里没有时，改用群主专属头衔的那段文字。
        // 这条通道是群主专属，普通成员不会被服务器接受；会把原因说清楚。
        String specialText = customText;
        if (specialText.isEmpty() && !off) {
            if (isOwnerRole(role) || "群主".equals(wantedName)) specialText = "群主";
            else if (isAdminRole(role) || "管理员".equals(wantedName)) specialText = "管理员";
        }

        JSONArray attempts = new JSONArray();
        boolean accepted = false;
        String source = "";
        String reason = "";
        String display = !specialText.isEmpty() ? specialText : titleName(titles, titleId);
        boolean customRequest = !customText.isEmpty();
        boolean identityFirst = titleId > 0 && !customRequest && !"special".equals(strategy);

        if (off) {
            if (owner) {
                PacketSvc.Result r = setSpecialTitle(g, self, "");
                attempts.put(attempt("special_clear", r.ok(), r.describe()));
            }
            int def = defaultTitleId(titles, selfRole);
            int wornId = before.optInt("worn_title_id");
            if (def > 0 && def != wornId) {
                ExtraSvc.Result r = wearByKernel(g, def);
                attempts.put(attempt("identity", r.ok(), r.describe()));
                accepted = r.ok();
                source = accepted ? "identity" : "";
                display = titleName(titles, def);
                if (accepted) titleId = def;
                if (!accepted) reason = failureReason(r.describe());
            } else {
                accepted = true;
                source = "default";
                display = titleName(titles, wornId);
            }
        } else if (identityFirst) {
            ExtraSvc.Result r = wearByKernel(g, titleId);
            attempts.put(attempt("identity", r.ok(), r.describe()));
            accepted = r.ok();
            source = accepted ? "identity" : "";
            if (!accepted && owner && !specialText.isEmpty()) {
                PacketSvc.Result s = setSpecialTitle(g, self, specialText);
                attempts.put(attempt("special", s.ok(), s.describe()));
                accepted = s.ok();
                source = accepted ? "special" : "";
                if (!accepted) reason = failureReason(s.describe());
            } else if (!accepted) {
                reason = failureReason(r.describe());
            }
        } else if (!specialText.isEmpty()) {
            if (owner) {
                PacketSvc.Result r = setSpecialTitle(g, self, specialText);
                attempts.put(attempt("special", r.ok(), r.describe()));
                accepted = r.ok();
                source = accepted ? "special" : "";
                if (!accepted) reason = failureReason(r.describe());
            } else {
                attempts.put(attempt("special", false, "only the group owner can write a custom title"));
                reason = "owner_required";
            }
        } else {
            reason = "not_available";
        }

        JSONObject out = new JSONObject()
                .put("guild_id", String.valueOf(g))
                .put("title_id", titleId > 0 ? titleId : 0)
                .put("title", display == null ? "" : display)
                .put("source", source)
                .put("role", selfRole)
                .put("can_set_special", owner)
                .put("accepted", accepted)
                .put("attempts", attempts)
                .put("msg", attemptsText(attempts))
                // ok 只表示这次请求被处理完了；真正有没有戴上请看 accepted / worn。
                // 这样“普通成员戴不上群主”不会反复触发发件熔断，也不会被误报成模块没更新。
                .put("ok", true);

        boolean worn = accepted;
        int wornId = titleId > 0 ? titleId : 0;
        String wornName = display == null ? "" : display;
        if (verify && accepted) {
            if (settle > 0) Thread.sleep(settle);
            JSONObject after = titleListJson(g, self);
            JSONArray list = after.optJSONArray("titles");
            worn = false;
            for (int i = 0; i < list.length(); i++) {
                JSONObject t = list.optJSONObject(i);
                if (t == null || !t.optBoolean("worn")) continue;
                wornName = t.optString("name", "");
                wornId = t.optInt("title_id");
                worn = "special".equals(source)
                        ? wornName.equals(display)
                        : ("identity".equals(source) ? wornId == titleId : true);
                break;
            }
            out.put("titles", list)
                    .put("worn_title_id", wornId)
                    .put("worn_name", wornName)
                    .put("worn", worn);
            if (!worn && reason.isEmpty()) reason = "not_reflected";
        } else {
            out.put("worn_title_id", wornId).put("worn_name", wornName).put("worn", worn);
        }
        if (!accepted && reason.isEmpty()) reason = "kernel_refused";
        if (!reason.isEmpty()) {
            out.put("reason", reason).put("hint", hintFor(reason, selfRole, display));
        }
        return out;
    }

    private static String failureReason(String detail) {
        String text = detail == null ? "" : detail.toLowerCase(Locale.ROOT);
        if (text.contains("timeout")) return "timeout";
        if (text.contains("1013") || text.contains("20000") || text.contains("permission")
                || text.contains("denied") || text.contains("not available")) return "owner_required";
        return "kernel_refused";
    }

    // ========================================================== 扩展动作注册表
    //
    // 每个动作在这里登记一次：规范名、别名、是否写入、参数说明，以及一次调用。
    // 新增动作前先核三件事：
    //   1. 类名在 QQ 的 dex 类索引里；
    //   2. 请求结构体的字段名（对单个类反编译核对，字段改名是静默失败）；
    //   3. 入口会不会回调（现场探测，只信 observed）。
    // 结构体字段的取值都记在下方注释里，取自本机 QQ 9.3.65 的反编译结果。

    /** 返回 {@link Result}（等内核回调）或 {@link org.json.JSONObject}（同步读）。 */
    public interface Fn { Object run(ExtraSvc s, JSONObject p) throws Exception; }

    /** 一个内核扩展动作。 */
    public static final class Spec {
        public final String name;
        public final String[] alias;
        /** 会改 QQ 那边的状态（capabilities 里归入写动作，失败要抛给调用方）。 */
        public final boolean write;
        /** 出站动作要不要排进 OutboundGate（限频、串行、熔断）。 */
        public final boolean gated;
        public final String params;
        public final Fn fn;
        Spec(String name, String[] alias, boolean write, boolean gated, String params, Fn fn) {
            this.name = name; this.alias = alias; this.write = write; this.gated = gated;
            this.params = params; this.fn = fn;
        }
    }

    private static final List<Spec> ACTIONS = new ArrayList<>();

    private static void reg(String name, String[] alias, boolean write, String params, Fn fn) {
        reg(name, alias, write, write, params, fn);
    }

    /** 写入但不必排出站队列的少数动作（见 typing）：写语义照旧，只是不占发送名额。 */
    private static void reg(String name, String[] alias, boolean write, boolean gated, String params, Fn fn) {
        ACTIONS.add(new Spec(name, alias, write, gated, params, fn));
    }

    public static List<Spec> actions() {
        return Collections.unmodifiableList(ACTIONS);
    }

    public static Spec find(String name) {
        if (name == null) return null;
        for (Spec s : ACTIONS) {
            if (s.name.equals(name)) return s;
            for (String a : s.alias) if (a.equals(name)) return s;
        }
        return null;
    }

    // ------------------------------------------------------------------ 取参

    /** 从 channel_id / guild_id+user_id 里取群号。 */
    static long groupOf(JSONObject p) {
        long g = p.optLong("guild_id", p.optLong("group_id", 0));
        if (g != 0) return g;
        String ch = p.optString("channel_id", "");
        if (!ch.isEmpty() && !Codec.isPrivateChannel(ch))
            return Codec.channelPeer(ch);
        return 0;
    }

    /** 会话 Contact（QQ 的 kernelpublic Contact）：群聊用群号，私聊用 UID。 */
    Object contactOf(JSONObject p) throws Exception {
        long g = groupOf(p);
        if (g != 0) return ref.neu(QQClient.CONTACT, QQClient.CT_GROUP, String.valueOf(g), "");
        String ch = p.optString("channel_id", "");
        long uin = 0;
        if (!ch.isEmpty() && Codec.isPrivateChannel(ch))
            uin = Codec.channelPeer(ch);
        else uin = parseUin(p.optString("user_id", ""));
        if (uin == 0) throw new IllegalArgumentException("missing channel_id");
        String uid = qq.resolveUid(uin);
        if (uid == null || uid.isEmpty()) uid = String.valueOf(uin);
        return ref.neu(QQClient.CONTACT, QQClient.CT_C2C, uid, "");
    }

    private Object neu(String cls, Object... args) { return ref.neu(cls, args); }
    private void put(Object o, String f, Object v) { if (v != null) ref.put(o, f, v); }
    private Object enumOf(String cls, String name) {
        try { return ref.getStatic(cls, name); } catch (Throwable t) { return null; }
    }

    private static ArrayList<Long> longs(JSONArray a) {
        ArrayList<Long> out = new ArrayList<>();
        for (int i = 0; a != null && i < a.length(); i++) out.add(a.optLong(i));
        return out;
    }
    private static ArrayList<String> strings(JSONArray a) {
        ArrayList<String> out = new ArrayList<>();
        for (int i = 0; a != null && i < a.length(); i++) out.add(a.optString(i, ""));
        return out;
    }
    private static ArrayList<Integer> ints(JSONArray a) {
        ArrayList<Integer> out = new ArrayList<>();
        for (int i = 0; a != null && i < a.length(); i++) out.add(a.optInt(i));
        return out;
    }

    /** 同步读到的东西直接包成 JSON；内核回调的读走 {@link Result}。 */
    private static JSONObject ok(JSONObject body) throws Exception {
        return body == null ? new JSONObject() : body;
    }

    private static final String[] NONE = new String[0];

    // ------------------------------------------------------ 新用到的回调接口

    private static final String INLINE_KEYBOARD_CB =
            "com.tencent.qqnt.kernel.nativeinterface.IClickInlineKeyboardButtonCallback";
    private static final String GROUP_ESSENCE_LATEST_CB =
            "com.tencent.qqnt.kernel.nativeinterface.IGetGroupLatestEssenceListCallback";
    private static final String ESSENCE_CACHED_CB =
            "com.tencent.qqnt.kernel.nativeinterface.IQueryCachedEssenceCallback";
    private static final String JOIN_GROUP_CB =
            "com.tencent.qqnt.kernel.nativeinterface.IJoinGroupCallback";
    private static final String JOIN_INFO_CB =
            "com.tencent.qqnt.kernel.nativeinterface.IGroupInfoForJoinCallback";
    private static final String BULLETIN_PIC_CB =
            "com.tencent.qqnt.kernel.nativeinterface.IUploadGroupBulletinPicCallback";
    private static final String GROUP_PROFILE_CARD_CB =
            "com.tencent.qqnt.kernel.nativeinterface.IGetGroupProfileCardInfoCallback";
    private static final String GROUP_ITEM_CB =
            "com.tencent.qqnt.kernel.nativeinterface.IGroupItemCallback";
    private static final String GROUP_FILE_RESULT_CB =
            "com.tencent.qqnt.kernel.nativeinterface.IGroupFileCommonResultCallback";
    private static final String DELETE_GROUP_FILE_CB =
            "com.tencent.qqnt.kernel.nativeinterface.IDeleteGroupFileCallback";
    private static final String RENAME_GROUP_FILE_CB =
            "com.tencent.qqnt.kernel.nativeinterface.IRenameGroupFileCallback";
    private static final String MOVE_GROUP_FILE_CB =
            "com.tencent.qqnt.kernel.nativeinterface.IMoveGroupFileCallback";
    private static final String TRANS_GROUP_FILE_CB =
            "com.tencent.qqnt.kernel.nativeinterface.ITransGroupFileCallback";
    private static final String OCR_SEARCH_CB =
            "com.tencent.qqnt.kernel.nativeinterface.ISearchOcrDataCallback";
    private static final String OCR_GET_CB =
            "com.tencent.qqnt.kernel.nativeinterface.IGetOcrDataCallback";
    private static final String AT_ALL_TIMES_CB =
            "com.tencent.qqnt.kernel.nativeinterface.IGetGroupRemainAtAllTimesOperateCallback";
    private static final String GROUP_FILE_INFO_CB =
            "com.tencent.qqnt.kernel.nativeinterface.IGroupFileInfoCallback";
    private static final String SEARCH_GROUP_FILE_CB =
            "com.tencent.qqnt.kernel.nativeinterface.ISearchGroupFileCallback";

    static {
        final String NS = "com.tencent.qqnt.kernel.nativeinterface.";

        // ------------------------------------------------------------ 消息

        // sendShowInputStatusReq(int chatType, int eventType, String peerUid, cb)
        //
        // 输入状态不是消息：它没有内容、没有回执意义、重复发也不会多出一条，所以不过
        // OutboundGate。过闸反而有害——每次「正在输入」都占一个出站名额（默认每秒一个、
        // 每分钟二十个），真话就得排在它后面，一串多气泡回复会被自己的输入状态拖慢。
        reg("typing", new String[]{"input_status", "set_input_status"}, true, false,
                "channel_id|guild_id|user_id, event_type?",
                (s, p) -> {
                    long g = groupOf(p);
                    String peer;
                    int chatType;
                    if (g != 0) { chatType = QQClient.CT_GROUP; peer = String.valueOf(g); }
                    else {
                        chatType = QQClient.CT_C2C;
                        long uin = parseUin(p.optString("user_id", ""));
                        String ch = p.optString("channel_id", "");
                        if (uin == 0 && !ch.isEmpty())
                            uin = Codec.channelPeer(ch);
                        if (uin == 0) throw new IllegalArgumentException("missing channel_id");
                        String uid = s.qq.resolveUid(uin);
                        peer = uid == null || uid.isEmpty() ? String.valueOf(uin) : uid;
                    }
                    final int eventType = p.optInt("event_type", 1);
                    return s.call(s.svc("msg"), OPERATE_CB, "sendShowInputStatusReq",
                            (svc2, cb) -> s.ref.call(svc2, "sendShowInputStatusReq",
                                    chatType, eventType, peer, cb));
                });

        reg("mark_read", NONE, true, "channel_id",
                (s, p) -> s.call(s.svc("msg"), OPERATE_CB, "setMsgRead",
                        (svc2, cb) -> s.ref.call(svc2, "setMsgRead", s.contactOf(p), cb)));

        reg("mark_read_seq", new String[]{"mark_read_message"}, true, "channel_id, message_seq",
                (s, p) -> s.call(s.svc("msg"), OPERATE_CB, "setSpecificMsgReadAndReport",
                        (svc2, cb) -> s.ref.call(svc2, "setSpecificMsgReadAndReport",
                                s.contactOf(p), p.optLong("message_seq", 0), cb)));

        reg("mark_all_read", NONE, true, "",
                (s, p) -> s.call(s.svc("msg"), OPERATE_CB, "setAllC2CAndGroupMsgRead",
                        (svc2, cb) -> s.ref.call(svc2, "setAllC2CAndGroupMsgRead", cb)));

        // getMsgAbstract(Contact, long msgSeq, IGetMsgAbstractsCallback)
        reg("message_abstract", NONE, false, "channel_id, message_seq",
                (s, p) -> s.call(s.svc("msg"), MSG_ABSTRACT_CB, "getMsgAbstract",
                        (svc2, cb) -> s.ref.call(svc2, "getMsgAbstract",
                                s.contactOf(p), p.optLong("message_seq", 0), cb)));

        // clickInlineKeyboardButton(InlineKeyboardClickInfo, IClickInlineKeyboardButtonCallback)
        // InlineKeyboardClickInfo: botAppid(long) buttonId(String) callbackData(String)
        //   chatType(int) dmFlag(int) guildId(String) msgSeq(long) peerId(String)
        reg("click_inline_keyboard", NONE, true,
                "channel_id, button_id, callback_data?, bot_appid?, msg_seq?",
                (s, p) -> {
                    Object info = s.neu(NS + "InlineKeyboardClickInfo");
                    long g = groupOf(p);
                    s.put(info, "botAppid", p.optLong("bot_appid", 0));
                    s.put(info, "buttonId", p.optString("button_id", ""));
                    s.put(info, "callbackData", p.optString("callback_data", ""));
                    s.put(info, "chatType", g != 0 ? QQClient.CT_GROUP : QQClient.CT_C2C);
                    s.put(info, "guildId", g != 0 ? String.valueOf(g) : "");
                    s.put(info, "msgSeq", p.optLong("msg_seq", 0));
                    s.put(info, "peerId", g != 0 ? String.valueOf(g) : p.optString("user_id", ""));
                    return s.call(s.svc("msg"), INLINE_KEYBOARD_CB, "clickInlineKeyboardButton",
                            (svc2, cb) -> s.ref.call(svc2, "clickInlineKeyboardButton", info, cb));
                });

        // reeditRecallMsg(Contact, long msgSeq, IOperateCallback)
        reg("reedit_recall", NONE, true, "channel_id, message_seq",
                (s, p) -> s.call(s.svc("msg"), OPERATE_CB, "reeditRecallMsg",
                        (svc2, cb) -> s.ref.call(svc2, "reeditRecallMsg",
                                s.contactOf(p), p.optLong("message_seq", 0), cb)));

        // ---- 图片 OCR（IKernelSearchService）。三个入口的参数语义未从反编译确定，
        //      这里按原样透传，标为 read：它们只落本地 OCR 库，不产生出站动作。
        reg("image_ocr", NONE, false, "path, a?, b?（参数语义未核，原样透传）",
                (s, p) -> {
                    final int a = p.optInt("a", 0);
                    final int b = p.optInt("b", 0);
                    final String path = p.optString("path", p.optString("image", ""));
                    return s.call(s.svc("search"), OPERATE_CB, "doOcrOnPicMsg",
                            (svc2, cb) -> s.ref.call(svc2, "doOcrOnPicMsg", path, a, b, cb));
                });

        reg("ocr_data", NONE, false, "keyword, a?, b?（参数语义未核）",
                (s, p) -> {
                    final String kw = p.optString("keyword", "");
                    final String b = p.optString("b", "");
                    final int c = p.optInt("c", 0);
                    return s.call(s.svc("search"), OCR_SEARCH_CB, "searchOcrData",
                            (svc2, cb) -> s.ref.call(svc2, "searchOcrData", kw, b, c, cb));
                });

        reg("ocr_by_aio", NONE, false, "peer, a?（参数语义未核）",
                (s, p) -> {
                    final String peer = p.optString("peer", p.optString("channel_id", ""));
                    final int a = p.optInt("a", 0);
                    return s.call(s.svc("search"), OCR_GET_CB, "getOcrDataByAIO",
                            (svc2, cb) -> s.ref.call(svc2, "getOcrDataByAIO", peer, a, cb));
                });

        // -------------------------------------------------------------- 群

        // getGroupRemainAtTimes(groupCode, IGetGroupRemainAtAllTimesOperateCallback)
        //   -> GroupAtInfo: canAtAll RemainAtAllCountForUin RemainAtAllCountForGroup canNotAtAllMsg
        // QQ 自己在输入框里打出 @ 时问的就是这个（面板上「@全体成员 剩余 N 次」）。
        // 与 satori-wx 同名同形：{allowed, remaining, group_remaining, reason}，没有上限的写 null。
        reg("at_all_quota", NONE, false, "channel_id|guild_id",
                (s, p) -> {
                    long g = groupOf(p);
                    if (g == 0) throw new IllegalArgumentException("missing channel_id");
                    Result r = s.call(s.svc("group"), AT_ALL_TIMES_CB, "getGroupRemainAtTimes",
                            (svc2, cb) -> s.ref.call(svc2, "getGroupRemainAtTimes", g, cb));
                    if (!r.ok() || r.payload == null)
                        throw new IllegalStateException("at_all_quota: " + r.describe());
                    Object info = r.payload;
                    Object can = s.ref.get(info, "canAtAll");
                    boolean allowed = can instanceof Boolean b ? b : Ref.asInt(can) != 0;
                    String reason = allowed ? "" : Ref.asStr(s.ref.get(info, "canNotAtAllMsg"));
                    return new JSONObject()
                            .put("allowed", allowed)
                            .put("remaining", Ref.asInt(s.ref.get(info, "RemainAtAllCountForUin")))
                            .put("group_remaining", Ref.asInt(s.ref.get(info, "RemainAtAllCountForGroup")))
                            .put("reason", reason == null ? "" : reason);
                });

        reg("group_shut_up_list", new String[]{"group_mute_list"}, false, "guild_id",
                (s, p) -> s.call(s.svc("group"), OPERATE_CB, "getGroupShutUpMemberList",
                        (svc2, cb) -> s.ref.call(svc2, "getGroupShutUpMemberList", groupOf(p), cb)));

        // getJoinGroupLink(GroupLinkReq, IGetJoinGroupLinkCallback)
        // GroupLinkReq: groupCode(long) srcId(int) needShortUrl(boolean) additionalParam(String)
        reg("group_join_link", new String[]{"group_share_link"}, false,
                "guild_id, src_id?, short_url?",
                (s, p) -> {
                    Object req = s.neu(NS + "GroupLinkReq");
                    s.put(req, "groupCode", groupOf(p));
                    s.put(req, "srcId", p.optInt("src_id", 73));
                    s.put(req, "needShortUrl", p.optBoolean("short_url", true));
                    s.put(req, "additionalParam", p.optString("additional_param", ""));
                    return s.call(s.svc("group"), JOIN_LINK_CB, "getJoinGroupLink",
                            (svc2, cb) -> s.ref.call(svc2, "getJoinGroupLink", req, cb));
                });

        // 群精华列表：内核 fetchGroupEssenceList 在本机 9.3.75/9.3.80 上调用即段错误，
        // 改走精华 H5 页自己的数据接口（QunWeb.essenceList）。
        reg("group_essence_list", NONE, false, "guild_id, pskey?",
                (s, p) -> {
                    long g = groupOf(p);
                    if (g == 0) throw new IllegalArgumentException("missing guild_id");
                    String pskey = s.pskey(p, "qun.qq.com");
                    JSONObject rsp = QunWeb.essenceList(s.qq.selfUin(), pskey, g);
                    JSONArray items = rsp.optJSONArray("essences");
                    if (items == null) items = new JSONArray();
                    return new JSONObject().put("ok", rsp.optBoolean("ok", false)).put("group", g)
                            .put("count", items.length()).put("essences", items)
                            .put("raw", rsp);
                });

        // getGroupLatestEssenceList(GetGroupLatestEssenceListReq, IGetGroupLatestEssenceListCallback)
        // GetGroupLatestEssenceListReq: groupCode(long) sign(String) pageCookie(String)
        //   msgType(int) excludeMsgType(int) signTs(long) source(int)
        reg("group_essence_latest", NONE, false, "guild_id, count?",
                (s, p) -> {
                    Object req = s.neu(NS + "GetGroupLatestEssenceListReq");
                    s.put(req, "groupCode", groupOf(p));
                    s.put(req, "msgType", p.optInt("msg_type", 0));
                    s.put(req, "excludeMsgType", p.optInt("exclude_msg_type", 0));
                    return s.call(s.svc("group"), GROUP_ESSENCE_LATEST_CB, "getGroupLatestEssenceList",
                            (svc2, cb) -> s.ref.call(svc2, "getGroupLatestEssenceList", req, cb));
                });

        // queryCachedEssenceMsg(EssenceKey, IQueryCachedEssenceCallback)
        // EssenceKey: groupCode(long) msgRandom(int) msgSeq(int)
        reg("group_essence_cached", NONE, false, "guild_id, msg_seq, msg_random",
                (s, p) -> {
                    Object key = s.neu(NS + "EssenceKey");
                    s.put(key, "groupCode", groupOf(p));
                    s.put(key, "msgSeq", p.optInt("msg_seq", 0));
                    s.put(key, "msgRandom", p.optInt("msg_random", 0));
                    return s.call(s.svc("group"), ESSENCE_CACHED_CB, "queryCachedEssenceMsg",
                            (svc2, cb) -> s.ref.call(svc2, "queryCachedEssenceMsg", key, cb));
                });

        // modifyGroupRemark(long groupCode, String remark, IOperateCallback)
        reg("group_remark", new String[]{"set_group_remark"}, true, "guild_id, remark",
                (s, p) -> s.call(s.svc("group"), OPERATE_CB, "modifyGroupRemark",
                        (svc2, cb) -> s.ref.call(svc2, "modifyGroupRemark",
                                groupOf(p), p.optString("remark", ""), cb)));

        // quitGroupV2(QuitGroupReq, IOperateCallback)
        // QuitGroupReq: groupCode(long) needDeleteLocalMsg(boolean)
        reg("group_quit", new String[]{"quit_group"}, true, "guild_id, delete_local_msg?",
                (s, p) -> {
                    Object req = s.neu(NS + "QuitGroupReq");
                    s.put(req, "groupCode", groupOf(p));
                    s.put(req, "needDeleteLocalMsg", p.optBoolean("delete_local_msg", false));
                    return s.call(s.svc("group"), OPERATE_CB, "quitGroupV2",
                            (svc2, cb) -> s.ref.call(svc2, "quitGroupV2", req, cb));
                });

        // reqToJoinGroup(ReqToGroup, IOperateCallback)
        // ReqToGroup: groupCode(long) sourceId(int) sourceSubId(int) richMsg(byte[])
        //   applyMsg(String) token(String) auth(String) noVerifyAuth(String) transInfo(byte[])
        reg("group_join", new String[]{"req_to_join_group"}, true,
                "guild_id, apply_msg?, source_id?, token?, auth?",
                (s, p) -> {
                    Object req = s.neu(NS + "ReqToGroup");
                    s.put(req, "groupCode", groupOf(p));
                    s.put(req, "sourceId", p.optInt("source_id", 0));
                    s.put(req, "sourceSubId", p.optInt("source_sub_id", 0));
                    if (p.has("apply_msg")) s.put(req, "applyMsg", p.optString("apply_msg", ""));
                    if (p.has("token")) s.put(req, "token", p.optString("token", ""));
                    if (p.has("auth")) s.put(req, "auth", p.optString("auth", ""));
                    return s.call(s.svc("group"), JOIN_GROUP_CB, "reqToJoinGroup",
                            (svc2, cb) -> s.ref.call(svc2, "reqToJoinGroup", req, cb));
                });

        // getGroupInfoForJoinGroup(long groupCode, boolean, int src, IGroupInfoForJoinCallback)
        reg("group_join_info", NONE, false, "guild_id, src?",
                (s, p) -> {
                    final int src = p.optInt("src", 0);
                    return s.call(s.svc("group"), JOIN_INFO_CB, "getGroupInfoForJoinGroup",
                            (svc2, cb) -> s.ref.call(svc2, "getGroupInfoForJoinGroup",
                                    groupOf(p), p.optBoolean("need_auth", false), src, cb));
                });

        // getJoinGroupNoVerifyFlag(long groupCode, String uid, IOperateCallback)
        reg("group_join_noverify", NONE, false, "guild_id, user_id",
                (s, p) -> {
                    final String uid = String.valueOf(parseUin(p.optString("user_id", "")));
                    return s.call(s.svc("group"), OPERATE_CB, "getJoinGroupNoVerifyFlag",
                            (svc2, cb) -> s.ref.call(svc2, "getJoinGroupNoVerifyFlag", groupOf(p), uid, cb));
                });

        // publishGroupBulletin(long groupCode, String psKey, GroupBulletinPublishReq, IOperateCallback)
        // GroupBulletinPublishReq: text(String) pinned(int) oldFeedsId(String?) picInfo(GroupBulletinPicInfo?)
        // text 要先做 encodeURI（NapCat 同一条内核路径的注释：「text 是 content 内容 url 编码」），
        // 原文直接塞进去，群里看到的是被服务端解码坏的乱码。psKey 不给就向内核要一份 qun.qq.com 的。
        // 配图先 uploadGroupBulletinPic 换成 picInfo；image_path 由 Dispatcher 从 image 解析好。
        reg("group_bulletin_publish", new String[]{"publish_group_bulletin"}, true,
                "guild_id, text, pinned?, image?（本地路径、file:、data:、http(s) 或 internal:）",
                (s, p) -> {
                    final long group = groupOf(p);
                    final String text = p.optString("text", "").trim();
                    if (group == 0 || text.isEmpty()) {
                        throw new IllegalArgumentException("guild_id and text are required");
                    }
                    final String pskey = s.pskey(p, "qun.qq.com");
                    Object req = s.neu(NS + "GroupBulletinPublishReq");
                    s.put(req, "text", encodeUri(text));
                    s.put(req, "pinned", p.optBoolean("pinned", false) || p.optInt("pinned", 0) == 1 ? 1 : 0);
                    if (p.has("old_feeds_id")) s.put(req, "oldFeedsId", p.optString("old_feeds_id", ""));
                    final String image = p.optString("image_path", "");
                    if (!image.isEmpty()) {
                        Result pic = s.call(s.svc("group"), BULLETIN_PIC_CB, "uploadGroupBulletinPic",
                                (svc2, cb) -> s.ref.call(svc2, "uploadGroupBulletinPic", group, pskey, image, cb));
                        if (!pic.ok() || pic.payload == null) return pic;
                        s.put(req, "picInfo", pic.payload);
                    }
                    return s.call(s.svc("group"), OPERATE_CB, "publishGroupBulletin",
                            (svc2, cb) -> s.ref.call(svc2, "publishGroupBulletin", group, pskey, req, cb));
                });

        // deleteGroupBulletin(long groupCode, String psKey, String feedsId, IOperateCallback)
        reg("group_bulletin_delete", new String[]{"delete_group_bulletin"}, true,
                "guild_id, feeds_id",
                (s, p) -> {
                    final String pskey = s.pskey(p, "qun.qq.com");
                    return s.call(s.svc("group"), OPERATE_CB, "deleteGroupBulletin",
                            (svc2, cb) -> s.ref.call(svc2, "deleteGroupBulletin", groupOf(p),
                                    pskey, p.optString("feeds_id", ""), cb));
                });

        // getGroupBulletin(long, IOperateCallback) 的回调只有 (code, msg)，拿不到正文；
        // 真读走 getGroupBulletinList(groupCode, pskey, "", req, IOperateCallback)，
        // 列表内容从 IKernelGroupListener.onGetGroupBulletinListResult(groupCode, wording, result) 回来。
        reg("group_bulletin_get", new String[]{"get_group_bulletin"}, false,
                "guild_id, count?, pskey?",
                (s, p) -> s.readGroupBulletins(groupOf(p), p.optInt("count", 10)));

        reg("group_ext_list", NONE, false, "force?",
                (s, p) -> s.call(s.svc("group"), OPERATE_CB, "getGroupExtList",
                        (svc2, cb) -> s.ref.call(svc2, "getGroupExtList",
                                p.optBoolean("force", true), cb)));

        reg("group_member_level", NONE, false, "guild_id",
                (s, p) -> s.call(s.svc("group"), OPERATE_CB, "getGroupMemberLevelInfo",
                        (svc2, cb) -> s.ref.call(svc2, "getGroupMemberLevelInfo", groupOf(p), cb)));

        // getIdentityList(GetIdentityListReq, boolean, IGetIdentityListCallback)
        // GetIdentityListReq: groupCode(long) memberUin(long)
        reg("group_identity_list", NONE, false, "guild_id, user_id?",
                (s, p) -> {
                    Object req = s.neu(NS + "GetIdentityListReq");
                    s.put(req, "groupCode", groupOf(p));
                    s.put(req, "memberUin", parseUin(p.optString("user_id", "")));
                    return s.call(s.svc("group"), IDENTITY_LIST_CB, "getIdentityList",
                            (svc2, cb) -> s.ref.call(svc2, "getIdentityList", req, true, cb));
                });

        // getIdentityList 的整理版：群头衔（群主 300、管理员 301、专属头衔 302、群等级 315）、
        // 账号在本群的角色，以及当前戴着哪一枚。
        reg("title_list", new String[]{"group_titles", "identity_titles"}, false,
                "guild_id, user_id?（默认自己）",
                (s, p) -> s.titleList(p));

        // 戴/换/摘群头衔。三条路：
        //   1. 身份目录里已有的头衔（300/301/302/315/其他）→ setIdentityTitleInfo；
        //   2. 群主想要「群主/管理员」但目录里没有 → 0x8FC_2 写专属头衔（302）；
        //   3. role=off → 清专用头衔，回到本角色的默认身份头衔。
        // 300/301 只有真的群主/管理员才会出现在目录里；普通成员硬报会被服务器回
        // 20000 medal not available，所以先在本地判断，不把一次注定失败的写入算成故障。
        reg("title_wear", new String[]{"group_title_wear", "fake_title", "wear_title", "identity_title_wear", "群头衔"}, true,
                "guild_id, title_id?|role?（owner/admin/level/off）|name?, title?（≤6字，群主专属）, strategy?（auto|identity|special）, verify?, settle_ms?",
                (s, p) -> s.wearTitle(p));

        // getGroupMemberCardInfo(GroupMemberCardInfoReq, IGetGroupMemberCardInfoCallback)
        // GroupMemberCardInfoReq: groupCode(long) memberUin(long)
        reg("group_member_card", NONE, false, "guild_id, user_id",
                (s, p) -> {
                    Object req = s.neu(NS + "GroupMemberCardInfoReq");
                    s.put(req, "groupCode", groupOf(p));
                    s.put(req, "memberUin", parseUin(p.optString("user_id", "")));
                    return s.call(s.svc("group"), MEMBER_CARD_CB, "getGroupMemberCardInfo",
                            (svc2, cb) -> s.ref.call(svc2, "getGroupMemberCardInfo", req, cb));
                });

        // getGroupProfileCardInfo(GetGroupProfileCardInfoReq, IGetGroupProfileCardInfoCallback)
        // GetGroupProfileCardInfoReq: groupCode(long) fetchMode(enum GroupProfileCardFetchMode)
        //   needAuthSign(boolean) needFetchOld88d(boolean) src(int) subSrc(int) thirdFileFeatureFlag(int)
        reg("group_profile_card", NONE, false, "guild_id, src?, sub_src?",
                (s, p) -> {
                    Object req = s.neu(NS + "GetGroupProfileCardInfoReq");
                    s.put(req, "groupCode", groupOf(p));
                    s.put(req, "src", p.optInt("src", 0));
                    s.put(req, "subSrc", p.optInt("sub_src", 0));
                    Object mode = s.enumOf(NS + "GroupProfileCardFetchMode",
                            p.optString("fetch_mode", "KFROMCACHE"));
                    if (mode != null) s.put(req, "fetchMode", mode);
                    return s.call(s.svc("group"), GROUP_PROFILE_CARD_CB, "getGroupProfileCardInfo",
                            (svc2, cb) -> s.ref.call(svc2, "getGroupProfileCardInfo", req, cb));
                });

        // batchGetGroupFileCount(ArrayList<Long>, IBatchGroupFileCountCallback)
        reg("group_file_count", NONE, false, "guild_ids[]",
                (s, p) -> {
                    final ArrayList<Long> gids = p.has("guild_ids")
                            ? longs(p.optJSONArray("guild_ids"))
                            : new ArrayList<>(Collections.singletonList(groupOf(p)));
                    return s.call(s.svc("richmedia"), BATCH_FILE_COUNT_CB, "batchGetGroupFileCount",
                            (svc2, cb) -> s.ref.call(svc2, "batchGetGroupFileCount", gids, cb));
                });

        // createGroupFolder(long groupCode, String folderName, IGroupItemCallback)
        reg("group_file_folder_create", NONE, true, "guild_id, name",
                (s, p) -> s.call(s.svc("richmedia"), GROUP_ITEM_CB, "createGroupFolder",
                        (svc2, cb) -> s.ref.call(svc2, "createGroupFolder",
                                groupOf(p), p.optString("name", ""), cb)));

        // deleteGroupFolder(long groupCode, String folderId, IGroupFileCommonResultCallback)
        reg("group_file_folder_delete", NONE, true, "guild_id, folder_id",
                (s, p) -> s.call(s.svc("richmedia"), GROUP_FILE_RESULT_CB, "deleteGroupFolder",
                        (svc2, cb) -> s.ref.call(svc2, "deleteGroupFolder",
                                groupOf(p), p.optString("folder_id", ""), cb)));

        // renameGroupFolder(long groupCode, String folderId, String newName, IGroupItemCallback)
        reg("group_file_folder_rename", NONE, true, "guild_id, folder_id, name",
                (s, p) -> s.call(s.svc("richmedia"), GROUP_ITEM_CB, "renameGroupFolder",
                        (svc2, cb) -> s.ref.call(svc2, "renameGroupFolder", groupOf(p),
                                p.optString("folder_id", ""), p.optString("name", ""), cb)));

        // deleteGroupFile(long groupCode, ArrayList<Integer> busIds, ArrayList<String> files, cb)
        reg("group_file_delete", NONE, true, "guild_id, files[], bus_ids?",
                (s, p) -> {
                    final ArrayList<Integer> bus = p.has("bus_ids")
                            ? ints(p.optJSONArray("bus_ids"))
                            : new ArrayList<>(Collections.singletonList(p.optInt("bus_id", 102)));
                    final ArrayList<String> files = strings(p.optJSONArray("files"));
                    return s.call(s.svc("richmedia"), DELETE_GROUP_FILE_CB, "deleteGroupFile",
                            (svc2, cb) -> s.ref.call(svc2, "deleteGroupFile", groupOf(p), bus, files, cb));
                });

        // renameGroupFile(long groupCode, int busId, String fileId, String parentId, String newName, cb)
        reg("group_file_rename", NONE, true, "guild_id, file_id, name, parent_id?, bus_id?",
                (s, p) -> s.call(s.svc("richmedia"), RENAME_GROUP_FILE_CB, "renameGroupFile",
                        (svc2, cb) -> s.ref.call(svc2, "renameGroupFile", groupOf(p),
                                p.optInt("bus_id", 102), p.optString("file_id", ""),
                                p.optString("parent_id", ""), p.optString("name", ""), cb)));

        // moveGroupFile(long, ArrayList<Integer> busIds, ArrayList<String> files,
        //               String parentFolderId, String destFolderId, cb)
        reg("group_file_move", NONE, true, "guild_id, files[], dest_folder_id, parent_id?, bus_ids?",
                (s, p) -> {
                    final ArrayList<Integer> bus = p.has("bus_ids")
                            ? ints(p.optJSONArray("bus_ids"))
                            : new ArrayList<>(Collections.singletonList(p.optInt("bus_id", 102)));
                    final ArrayList<String> files = strings(p.optJSONArray("files"));
                    return s.call(s.svc("richmedia"), MOVE_GROUP_FILE_CB, "moveGroupFile",
                            (svc2, cb) -> s.ref.call(svc2, "moveGroupFile", groupOf(p), bus, files,
                                    p.optString("parent_id", ""), p.optString("dest_folder_id", ""), cb));
                });

        // transGroupFile(long groupCode, String fileId, ITransGroupFileCallback)
        reg("group_file_trans", NONE, true, "guild_id, file_id",
                (s, p) -> s.call(s.svc("richmedia"), TRANS_GROUP_FILE_CB, "transGroupFile",
                        (svc2, cb) -> s.ref.call(svc2, "transGroupFile",
                                groupOf(p), p.optString("file_id", ""), cb)));

        // 头衔/身份开关。内核写路径复用 QQClient 已核过的那两条。
        reg("identity_title_info", new String[]{"set_identity_title_info"}, true, "guild_id, show?",
                (s, p) -> s.opJson(s.qq.setIdentityTitleInfo(groupOf(p), p.optBoolean("show", true))));

        reg("identity_level_info", new String[]{"set_identity_level_info"}, true, "guild_id, show?",
                (s, p) -> s.opJson(s.qq.setGroupIdentityLevelInfo(groupOf(p), p.optBoolean("show", true))));

        // --- 群身份实验动作：普通成员能不能从别的入口把文字送到“头衔”位置 ---
        // 读群身份的完整管理设置（等级名、各展示开关）。
        reg("identity_manage", NONE, false, "guild_id",
                (s, p) -> {
                    Object req = s.neu(NS + "GIMGetManageSettingReq");
                    s.put(req, "groupCode", groupOf(p));
                    return s.call(s.svc("group"),
                            "com.tencent.qqnt.kernel.nativeinterface.IGetGroupIdentityManageSettingCallback",
                            "getGroupIdentityManageSetting",
                            (svc2, cb) -> s.ref.call(svc2, "getGroupIdentityManageSetting", req, cb));
                });

        // setGroupIdentityLevelInfo(GIMSetGroupLevelInfoReq, cb)
        // Req: {groupCode, levelFlag, levelNames: ArrayList<GIMLevelName>, levelNewFlag, useNewLevel}
        reg("identity_level_names", NONE, true, "guild_id, level_names[], level_flag?, level_new_flag?, use_new_level?",
                (s, p) -> {
                    Object req = s.neu(NS + "GIMSetGroupLevelInfoReq");
                    s.put(req, "groupCode", groupOf(p));
                    s.put(req, "levelFlag", p.optInt("level_flag", 1));
                    s.put(req, "levelNewFlag", p.optInt("level_new_flag", 1));
                    s.put(req, "useNewLevel", p.optInt("use_new_level", 0));
                    // levelNames 是 ArrayList<GIMLevelName>，不是字符串数组。之前直接塞
                    // ArrayList<String>，native 侧按对象字段取值时直接崩，所以“设等级名”
                    // 一直失败。这里按官方的 {level:int, name:string} 形状构造。
                    JSONArray names = p.optJSONArray("level_names");
                    ArrayList<Object> levelNames = new ArrayList<>();
                    if (names != null) {
                        for (int i = 0; i < names.length(); i++) {
                            Object item = names.opt(i);
                            Object ln = s.neu(NS + "GIMLevelName");
                            if (item instanceof JSONObject obj) {
                                s.put(ln, "level", obj.optInt("level", i + 1));
                                s.put(ln, "name", obj.optString("name", ""));
                            } else {
                                s.put(ln, "level", i + 1);
                                s.put(ln, "name", item == null ? "" : String.valueOf(item));
                            }
                            levelNames.add(ln);
                        }
                    }
                    s.put(req, "levelNames", levelNames);
                    return s.call(s.svc("group"),
                            "com.tencent.qqnt.kernel.nativeinterface.ISetGroupIdentityLevelInfoCallback",
                            "setGroupIdentityLevelInfo",
                            (svc2, cb) -> s.ref.call(svc2, "setGroupIdentityLevelInfo", req, cb));
                });


        // ------------------------------------------------------- 资料与好友

        // getUserDetailInfo(String uid, IOperateCallback) —— 回调只有 code/msg。
        reg("user_detail", NONE, false, "user_id",
                (s, p) -> {
                    final String uid = s.uidForUin(p.optString("user_id", ""));
                    return s.call(s.svc("profile"), OPERATE_CB, "getUserDetailInfo",
                            (svc2, cb) -> s.ref.call(svc2, "getUserDetailInfo", uid, cb));
                });

        // getUserDetailInfoByUin(long uin, IDetailInfoByUinCallback)
        reg("user_detail_by_uin", NONE, false, "user_id",
                (s, p) -> {
                    final long uin = parseUin(p.optString("user_id", ""));
                    return s.call(s.svc("profile"), DETAIL_BY_UIN_CB, "getUserDetailInfoByUin",
                            (svc2, cb) -> s.ref.call(svc2, "getUserDetailInfoByUin", uin, cb));
                });

        // getUserSimpleInfo(boolean, ArrayList<String> uids, IOperateCallback)
        reg("user_simple_info", NONE, false, "user_ids[]",
                (s, p) -> {
                    final ArrayList<String> uids = new ArrayList<>();
                    JSONArray a = p.optJSONArray("user_ids");
                    if (a == null && p.has("user_id")) a = new JSONArray().put(p.opt("user_id"));
                    for (int i = 0; a != null && i < a.length(); i++) uids.add(s.uidForUin(a.optString(i, "")));
                    return s.call(s.svc("profile"), OPERATE_CB, "getUserSimpleInfo",
                            (svc2, cb) -> s.ref.call(svc2, "getUserSimpleInfo", true, uids, cb));
                });

        // setLongNick(String, IOperateCallback)
        reg("long_nick", new String[]{"set_long_nick", "signature"}, true, "long_nick|text",
                (s, p) -> s.call(s.svc("profile"), OPERATE_CB, "setLongNick",
                        (svc2, cb) -> s.ref.call(svc2, "setLongNick",
                                p.optString("long_nick", p.optString("text", p.optString("signature", ""))), cb)));

        // setNickName(String, IOperateCallback) —— QQ 昵称（账号级，不是群名片）。
        reg("nick", new String[]{"set_nick", "nick_name"}, true, "nick",
                (s, p) -> s.call(s.svc("profile"), OPERATE_CB, "setNickName",
                        (svc2, cb) -> s.ref.call(svc2, "setNickName",
                                p.optString("nick", p.optString("nickname", "")), cb)));

        // setHeader(String path, IOperateCallback) —— QQ 头像，内核只收本地文件路径，
        // 链接与 data: 由 Media.resolve 先落盘。
        reg("avatar", new String[]{"set_avatar"}, true, "image（本地路径、http(s)、data:）",
                (s, p) -> {
                    File image = Media.resolve(p.optString("image", p.optString("url", "")), "");
                    if (image == null || !image.isFile())
                        throw new IllegalArgumentException("cannot resolve avatar image");
                    return s.call(s.svc("profile"), OPERATE_CB, "setHeader",
                            (svc2, cb) -> s.ref.call(svc2, "setHeader", image.getAbsolutePath(), cb));
                });

        // getPskey(ArrayList<String> domains, boolean force, IGetPskeyCallback)
        //   -> onFetchPskey(code, msg, HashMap<domain, pskey>)
        // QZone 等网页业务靠它拿空间域的 p_skey；空结果再强制刷一次。
        reg("pskey", NONE, false, "domains[]（默认 qzone.qq.com）",
                (s, p) -> {
                    ArrayList<String> domains = strings(p.optJSONArray("domains"));
                    if (domains.isEmpty()) domains.add("qzone.qq.com");
                    Map<?, ?> map = s.fetchPskeyMap(domains);
                    JSONObject out = new JSONObject();
                    for (Map.Entry<?, ?> e : map.entrySet())
                        out.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
                    return out;
                });

        // QZone 动态（说说）：H5 CGI + 空间域 p_skey，见 Qzone。
        reg("feed_publish", new String[]{"publish_feed", "qzone_publish"}, true,
                "text, auto_sig?, to_sign?, qzonetoken?",
                (s, p) -> Qzone.publish(s.qq.selfUin(), s.pskey(p, "qzone.qq.com"), p));

        // searchGroupFileByWord(ArrayList<Long> uins, ArrayList<Long> busIds, String word,
        //     String folderId, int count, ISearchGroupFileCallback) —— 群文件按名搜索。
        reg("group_file_search", NONE, false, "guild_id, word, count?",
                (s, p) -> {
                    ArrayList<Long> groups = new ArrayList<>();
                    groups.add(groupOf(p));
                    return s.call(s.svc("richmedia"), SEARCH_GROUP_FILE_CB, "searchGroupFileByWord",
                            (svc2, cb) -> s.ref.call(svc2, "searchGroupFileByWord", groups,
                                    new ArrayList<Long>(), p.optString("word", ""),
                                    p.optString("folder_id", ""), p.optInt("count", 20), cb));
                });

        // getGroupFileInfo(groupCode, fileId, IGroupFileInfoCallback) —— 单个群文件的
        // 详情（含已下载的本地路径）。
        reg("group_file_get", NONE, false, "guild_id, file_id",
                (s, p) -> s.call(s.svc("richmedia"), GROUP_FILE_INFO_CB, "getGroupFileInfo",
                        (svc2, cb) -> s.ref.call(svc2, "getGroupFileInfo", groupOf(p),
                                p.optString("file_id", ""), cb)));

        // getGroupFileList(groupCode, GroupFileListForm) 的结果从
        // IKernelMsgListener.onGroupFileInfoUpdate 回来，按 GroupItem.peerId 对上群号；
        // 翻页循环在 listGroupFiles 里。
        reg("group_file_list", new String[]{"group_files"}, false,
                "guild_id, folder_id?, start_index?, limit?",
                (s, p) -> s.listGroupFiles(groupOf(p), p.optString("folder_id", ""),
                        p.optInt("start_index", 0), p.optInt("limit", 50)));

        // onlyDownloadFile(Contact, ArrayList<DownloadGroupFileParams>) —— 下载群文件。
        // 没有回调返回值，完成通知从 onRichMediaDownloadComplete 按 fileId 唤醒；
        // 等不来就按 getGroupFileInfo 的本地路径兜底，再不行才报超时。
        reg("group_file_download", NONE, true,
                "guild_id, file_id, file_name?, file_model_id?, file_size?",
                (s, p) -> {
                    long g = groupOf(p);
                    String fileId = p.optString("file_id", "");
                    if (g == 0 || fileId.isEmpty())
                        throw new IllegalArgumentException("missing guild_id/file_id");
                    final long[] meta = {p.optLong("file_model_id", 0), p.optLong("file_size", 0)};
                    final String[] name = {p.optString("file_name", "")};
                    if (meta[0] == 0 || meta[1] == 0 || name[0].isEmpty()) {
                        Result info = s.call(s.svc("richmedia"), GROUP_FILE_INFO_CB, "getGroupFileInfo",
                                (svc2, cb) -> s.ref.call(svc2, "getGroupFileInfo", g, fileId, cb));
                        if (info.ok() && info.payload != null) {
                            Object fi = s.ref.get(info.payload, "fileInfo");
                            if (fi == null) fi = info.payload;
                            if (meta[0] == 0) meta[0] = Ref.asLong(s.ref.get(fi, "fileModelId"));
                            if (meta[1] == 0) meta[1] = Ref.asLong(s.ref.get(fi, "fileSize"));
                            if (name[0].isEmpty()) name[0] = Ref.asStr(s.ref.get(fi, "fileName"));
                        }
                    }
                    QQClient.TransWait wait = s.qq.newTransWait(fileId);
                    Object contact = s.contactOf(p);
                    Object params = s.ref.neu("com.tencent.qqnt.kernel.nativeinterface.DownloadGroupFileParams");
                    s.ref.put(params, "fileId", fileId);
                    s.ref.put(params, "fileModelId", meta[0]);
                    s.ref.put(params, "fileName", name[0]);
                    s.ref.put(params, "fileSize", meta[1]);
                    ArrayList<Object> one = new ArrayList<>();
                    one.add(params);
                    Object media = s.svc("richmedia");
                    s.onMain(() -> {
                        try {
                            s.ref.call(media, "onlyDownloadFile", contact, one);
                        } catch (Throwable t) {
                            L.e("ExtraSvc group_file_download", t);
                            wait.latch.countDown();
                        }
                    });
                    boolean arrived = wait.latch.await(3, TimeUnit.MINUTES);
                    String path = wait.path;
                    long err = wait.errorCode;
                    if (!arrived || err != 0 || path.isEmpty()) {
                        // 完成通知没来（或没等满）：问一下本地路径，下载完的东西在那儿。
                        Result info = s.call(s.svc("richmedia"), GROUP_FILE_INFO_CB, "getGroupFileInfo",
                                (svc2, cb) -> s.ref.call(svc2, "getGroupFileInfo", g, fileId, cb));
                        if (info.ok() && info.payload != null) {
                            Object fi = s.ref.get(info.payload, "fileInfo");
                            if (fi == null) fi = info.payload;
                            String local = Ref.asStr(s.ref.get(fi, "localPath"));
                            if (!local.isEmpty()) { path = local; err = 0; }
                        }
                    }
                    JSONObject out = new JSONObject();
                    out.put("ok", err == 0 && !path.isEmpty())
                            .put("code", (int) err).put("msg", err == 0 ? "" : wait.errorMessage)
                            .put("file_id", fileId).put("file_name", name[0])
                            .put("file_size", meta[1]).put("path", path);
                    if (!arrived) out.put("assumed", true);
                    return out;
                });

        // onlyUploadFile(Contact, ArrayList<UploadGroupFileParams>) —— 上传群文件。
        // 完成通知从 onRichMediaUploadComplete 按 filePath 尾段对回来；等不来标 assumed。
        reg("group_file_upload", NONE, true, "guild_id, path（本地路径/http(s)/data:）, name?",
                (s, p) -> {
                    long g = groupOf(p);
                    if (g == 0) throw new IllegalArgumentException("missing guild_id");
                    File local = Media.resolve(p.optString("path", p.optString("file", "")), "");
                    if (local == null || !local.isFile())
                        throw new IllegalArgumentException("cannot resolve upload file");
                    String name = p.optString("name", local.getName());
                    QQClient.TransWait wait = s.qq.newTransWait(name);
                    Object contact = s.contactOf(p);
                    Object params = s.ref.neu("com.tencent.qqnt.kernel.nativeinterface.UploadGroupFileParams");
                    s.ref.put(params, "fileModelId", 0L);
                    s.ref.put(params, "fileName", name);
                    s.ref.put(params, "filePath", local.getAbsolutePath());
                    ArrayList<Object> one = new ArrayList<>();
                    one.add(params);
                    Object media = s.svc("richmedia");
                    s.onMain(() -> {
                        try {
                            s.ref.call(media, "onlyUploadFile", contact, one);
                        } catch (Throwable t) {
                            L.e("ExtraSvc group_file_upload", t);
                            wait.latch.countDown();
                        }
                    });
                    boolean arrived = wait.latch.await(3, TimeUnit.MINUTES);
                    JSONObject out = new JSONObject();
                    out.put("ok", arrived ? wait.errorCode == 0 : true)
                            .put("code", (int) wait.errorCode)
                            .put("msg", wait.errorMessage)
                            .put("file_name", name).put("file_size", local.length())
                            .put("local_path", local.getAbsolutePath());
                    if (arrived && !wait.path.isEmpty()) out.put("path", wait.path);
                    if (!arrived) out.put("assumed", true);
                    return out;
                });

        // getBuddyRemark(ArrayList<String> uids) —— 同步读，回 HashMap<String,String>
        reg("friend_remark_get", NONE, false, "user_ids[]",
                (s, p) -> {
                    ArrayList<String> uids = new ArrayList<>();
                    JSONArray a = p.optJSONArray("user_ids");
                    if (a == null && p.has("user_id")) a = new JSONArray().put(p.opt("user_id"));
                    for (int i = 0; a != null && i < a.length(); i++) uids.add(s.uidForUin(a.optString(i, "")));
                    Object map = s.ref.call(s.svc("buddy"), "getBuddyRemark", uids);
                    return new JSONObject().put("ok", true)
                            .put("remark", s.mapJson(map));
                });

        // setBuddyRemark(RemarkParams, IOperateCallback)
        // RemarkParams: uid(String) remark(String) signInfo(RemarkSignExtInfo)
        reg("friend_remark_set", new String[]{"set_friend_remark"}, true, "user_id, remark",
                (s, p) -> {
                    Object req = s.neu(NS + "RemarkParams");
                    s.put(req, "uid", s.uidForUin(p.optString("user_id", "")));
                    s.put(req, "remark", p.optString("remark", ""));
                    return s.call(s.svc("buddy"), OPERATE_CB, "setBuddyRemark",
                            (svc2, cb) -> s.ref.call(svc2, "setBuddyRemark", req, cb));
                });

        reg("friend_category_add", NONE, true, "name",
                (s, p) -> s.call(s.svc("buddy"), OPERATE_CB, "addCategory",
                        (svc2, cb) -> s.ref.call(svc2, "addCategory", p.optString("name", ""), cb)));

        reg("friend_category_delete", NONE, true, "category_id",
                (s, p) -> s.call(s.svc("buddy"), OPERATE_CB, "delCategory",
                        (svc2, cb) -> s.ref.call(svc2, "delCategory", p.optInt("category_id", 0), cb)));

        reg("friend_category_rename", NONE, true, "category_id, name",
                (s, p) -> s.call(s.svc("buddy"), OPERATE_CB, "renameCategory",
                        (svc2, cb) -> s.ref.call(svc2, "renameCategory",
                                p.optInt("category_id", 0), p.optString("name", ""), cb)));

        reg("friend_category_set", NONE, true, "user_id, category_id",
                (s, p) -> {
                    final String uid = s.uidForUin(p.optString("user_id", ""));
                    final int id = p.optInt("category_id", 0);
                    return s.call(s.svc("buddy"), OPERATE_CB, "setBuddyCategory",
                            (svc2, cb) -> s.ref.call(svc2, "setBuddyCategory", uid, id, cb));
                });

        reg("friend_category_set_batch", NONE, true, "user_ids[], category_id",
                (s, p) -> {
                    final ArrayList<String> uids = new ArrayList<>();
                    JSONArray a = p.optJSONArray("user_ids");
                    for (int i = 0; a != null && i < a.length(); i++) uids.add(s.uidForUin(a.optString(i, "")));
                    final int id = p.optInt("category_id", 0);
                    return s.call(s.svc("buddy"), OPERATE_CB, "setBatchBuddyCategory",
                            (svc2, cb) -> s.ref.call(svc2, "setBatchBuddyCategory", uids, id, cb));
                });

        // reqToAddFriends(ReqToFriend, IOperateCallback)
        // ReqToFriend: buddyUin(long) buddyUid(String) phoneNumber(String) addFriendSetting(int)
        //   answer(String) remark(String) defaultCatgory(Integer) verifyInfo(String)
        //   securityVerify(Verify) sourceID(int) sourceSubID(int)
        reg("friend_add", new String[]{"add_friend"}, true,
                "user_id, remark?, verify_info?, answer?, source_id?",
                (s, p) -> {
                    Object req = s.neu(NS + "ReqToFriend");
                    final long uin = parseUin(p.optString("user_id", ""));
                    s.put(req, "buddyUin", uin);
                    s.put(req, "buddyUid", s.uidForUin(p.optString("user_id", "")));
                    s.put(req, "addFriendSetting", p.optInt("add_friend_setting", 0));
                    s.put(req, "remark", p.optString("remark", ""));
                    s.put(req, "verifyInfo", p.optString("verify_info", ""));
                    s.put(req, "answer", p.optString("answer", ""));
                    s.put(req, "sourceID", p.optInt("source_id", 3999));
                    s.put(req, "sourceSubID", p.optInt("source_sub_id", 0));
                    return s.call(s.svc("buddy"), OPERATE_CB, "reqToAddFriends",
                            (svc2, cb) -> s.ref.call(svc2, "reqToAddFriends", req, cb));
                });

        // ------------------------------------------------- 在线文件与临时会话

        // getOnlineFileMsgs(Contact, IMsgOperateCallback) / getAllOnlineFileMsgs(IMsgOperateCallback)
        reg("online_file_list", NONE, false, "channel_id?",
                (s, p) -> {
                    if (!p.has("channel_id") && !p.has("guild_id") && !p.has("user_id")) {
                        return s.call(s.svc("msg"), MSG_OPERATE_CB, "getAllOnlineFileMsgs",
                                (svc2, cb) -> s.ref.call(svc2, "getAllOnlineFileMsgs", cb));
                    }
                    return s.call(s.svc("msg"), MSG_OPERATE_CB, "getOnlineFileMsgs",
                            (svc2, cb) -> s.ref.call(svc2, "getOnlineFileMsgs", s.contactOf(p), cb));
                });

        // refuseReceiveOnlineFileMsg(Contact, long msgSeq, IOperateCallback)
        reg("online_file_refuse", NONE, true, "channel_id, message_seq",
                (s, p) -> s.call(s.svc("msg"), OPERATE_CB, "refuseReceiveOnlineFileMsg",
                        (svc2, cb) -> s.ref.call(svc2, "refuseReceiveOnlineFileMsg",
                                s.contactOf(p), p.optLong("message_seq", 0), cb)));

        // getTempChatInfo(int chatType, String uid, IGetTempChatInfoCallback)
        reg("temp_chat_info", NONE, false, "guild_id?|user_id, chat_type?",
                (s, p) -> {
                    final long g = groupOf(p);
                    final int chatType = p.optInt("chat_type", g != 0 ? QQClient.CT_GROUP : QQClient.CT_C2C);
                    final String peer = g != 0
                            ? String.valueOf(g)
                            : s.uidForUin(p.optString("user_id", ""));
                    return s.call(s.svc("msg"), TEMP_CHAT_CB, "getTempChatInfo",
                            (svc2, cb) -> s.ref.call(svc2, "getTempChatInfo", chatType, peer, cb));
                });
    }

    /** uin → uid；取不到就回原值（有些入口收的就是 uin 字符串）。 */
    private String uidForUin(String raw) {
        long uin = parseUin(raw);
        if (uin == 0) return raw == null ? "" : raw;
        try {
            String uid = qq.resolveUid(uin);
            if (uid != null && !uid.isEmpty()) return uid;
        } catch (Throwable ignore) {}
        return String.valueOf(uin);
    }


    /**
     * 群公告这类 web 入口要的 p_skey。调用方给了就用它的；没给就向内核要
     * （{@code IKernelTipOffService.getPskey(domains, cacheFirst, cb)}，回调第三参是 域名→pskey 的表）。
     */
    private String pskey(JSONObject p, String domain) throws Exception {
        String given = p.optString("pskey", "");
        if (!given.isEmpty()) return given;
        ArrayList<String> domains = new ArrayList<>(Collections.singletonList(domain));
        Map<?, ?> map = fetchPskeyMap(domains);
        Object value = map.get(domain);
        if (value != null && !String.valueOf(value).isEmpty()) return String.valueOf(value);
        throw new IllegalStateException("pskey for " + domain + " unavailable");
    }

    /**
     * 拉指定域的 p_skey 表。走登录服务的 {@code guestA2ToPskey(uin, domains, cb)}：访客签发，
     * 同域同票，公告与空间的网页 CGI 都认它。回调类型必须是 IGuestPskeyCallback。
     *
     * <p>不再走会话的 {@code IKernelTipOffService.getPskey}：9.3.75 上该服务为 null，
     * 9.3.80 上能取到对象但调用即段错误（libkernel.so+0x192f918）。
     */
    private Map<?, ?> fetchPskeyMap(ArrayList<String> domains) throws Exception {
        // 9.3.80：会话的 TipOff 服务能取到对象，但 getPskey 的 native 入口必段错误
        // （libkernel.so+0x192f918，CrashDefend 吞掉 signal 11 后 QQ 重启）。所以不走 TipOff。
        Object login = qq.getLoginService();
        if (login == null) throw new IllegalStateException("no pskey service (login unavailable)");
        Result r = call(login, GUEST_PSKEY_CB, "guestA2ToPskey",
                (svc, cb) -> ref.call(svc, "guestA2ToPskey", parseUin(qq.selfUin()), domains, cb));
        if (r.ok() && r.payload instanceof Collection<?> keys) {
            Map<String, String> map = new HashMap<>();
            for (Object item : keys) {
                String domain = Ref.asStr(ref.get(item, "domain"));
                String key = Ref.asStr(ref.get(item, "key"));
                if (!domain.isEmpty() && !key.isEmpty()) map.put(domain, key);
            }
            if (!map.isEmpty()) return map;
        }
        throw new IllegalStateException("pskey unavailable: " + r.describe());
    }

    /** 与 JavaScript 的 {@code encodeURI} 相同：保留 URI 结构字符，其余按 UTF-8 百分号编码。 */
    static String encodeUri(String text) {
        final String keep = ";,/?:@&=+$-_.!~*'()#";
        StringBuilder out = new StringBuilder(text.length() * 2);
        for (byte b : text.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || (c < 0x80 && keep.indexOf(c) >= 0)) {
                out.append((char) c);
            } else {
                out.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
                        .append(Character.toUpperCase(Character.forDigit(c & 15, 16)));
            }
        }
        return out.toString();
    }

    // ------------------------------------------------------------ 群文件列表

    /**
     * 群文件列表：内核 {@code getGroupFileList} 只回一个请求号，真正的条目从
     * {@code IKernelMsgListener.onGroupFileInfoUpdate} 按 GroupItem.peerId 送回来。
     * 翻页用返回里的 {@code nextIndex}，直到 {@code isEnd} 或攒够 {@code limit}。
     */
    private Object listGroupFiles(long group, String folderId, int startIndex, int limit)
            throws Exception {
        if (group == 0) throw new IllegalArgumentException("missing guild_id");
        Object svc = svc("richmedia");
        if (svc == null) throw new IllegalStateException("group file list: service not ready");
        ArrayList<Object> items = new ArrayList<>();
        int index = startIndex;
        int allCount = -1;
        boolean end = false;
        int pages = 0;
        while (!end && items.size() < Math.max(1, limit) && pages++ < 20) {
            final Object[] box = new Object[1];
            final CountDownLatch page = new CountDownLatch(1);
            qq.awaitGroupFileList(group, result -> {
                if (box[0] == null) box[0] = result;
                page.countDown();
                return true;
            });
            Object form = ref.neu("com.tencent.qqnt.kernel.nativeinterface.GroupFileListForm");
            ref.put(form, "folderId", folderId == null ? "" : folderId);
            ref.put(form, "fileCount", Math.min(40, Math.max(10, limit - items.size())));
            ref.put(form, "startIndex", index);
            ref.put(form, "sortOrder", 0);
            ref.put(form, "showOnlinedocFolder", 0);
            int req = syncInt(svc, "getGroupFileList", group, form);
            if (req < 0 || !page.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                end = req >= 0; // 请求发了但没回包：把已拿到的条目交出去
                break;
            }
            Object result = box[0];
            Object list = ref.get(result, "item");
            if (list instanceof Collection<?> rows) {
                items.addAll((Collection<Object>) rows);
            }
            allCount = Ref.asInt(ref.get(result, "allFileCount"));
            end = Ref.asLong(ref.get(result, "isEnd")) != 0;
            int next = Ref.asInt(ref.get(result, "nextIndex"));
            if (next <= index) end = true;
            index = next;
        }
        JSONObject out = new JSONObject();
        out.put("group", group).put("folder_id", folderId)
                .put("all_file_count", allCount).put("is_end", end).put("next_index", index);
        JSONArray rows = new JSONArray();
        for (Object item : items) {
            Object info = ref.get(item, "fileInfo");
            Object folder = ref.get(item, "folderInfo");
            if (info != null) rows.put(reflectItem(info));
            if (folder != null) rows.put(reflectItem(folder));
        }
        out.put("items", rows);
        return out;
    }

    /** 单个内核对象的字段导出；嵌套一层为止。 */
    private JSONObject reflectItem(Object o) throws Exception {
        JSONObject out = new JSONObject();
        for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(o);
                    if (v == null || v instanceof String || v instanceof Number || v instanceof Boolean) {
                        out.put(f.getName(), v == null ? null : v);
                    } else if (v instanceof Collection<?> col) {
                        out.put(f.getName(), new JSONArray(col.stream()
                                .map(x -> x == null ? null : String.valueOf(x)).toArray()));
                    } else if (v instanceof Map<?, ?> map) {
                        JSONObject m = new JSONObject();
                        for (Map.Entry<?, ?> e : map.entrySet()) {
                            m.put(String.valueOf(e.getKey()),
                                    e.getValue() == null ? null : String.valueOf(e.getValue()));
                        }
                        out.put(f.getName(), m);
                    } else {
                        out.put(f.getName(), String.valueOf(v));
                    }
                } catch (Throwable ignore) {
                    // 读不出的字段不导出
                }
            }
        }
        return out;
    }

    // -------------------------------------------------------- 群公告 / 群精华的读

    /**
     * 群公告列表：内核 {@code getGroupBulletinList} 在本机 9.3.75/9.3.80 上调用即段错误，改走
     * 客户端公告页自己的网页 CGI（web.qun.qq.com/announce/get_t_list），p_skey 本端自取。
     */
    private Object readGroupBulletins(long group, int count) throws Exception {
        if (group == 0) throw new IllegalArgumentException("missing guild_id");
        String pskey = pskey(new JSONObject(), "qun.qq.com");
        JSONObject rsp = QunWeb.bulletinList(qq.selfUin(), pskey, group);
        JSONArray items = rsp.optJSONArray("bulletins");
        if (items == null) items = new JSONArray();
        return new JSONObject().put("ok", rsp.optBoolean("ok", false)).put("group", group)
                .put("count", items.length()).put("bulletins", items)
                .put("raw", rsp);
    }

    /** 内核调用一律投到主 Looper 发起（服务是主线程亲和的），本线程继续等。 */
    private void onMain(Runnable task) {
        Looper main = Looper.getMainLooper();
        if (main != null && Looper.myLooper() != main) {
            new Handler(main).post(task);
        } else {
            task.run();
        }
    }

    /** 同步返回的内核方法（回调在别处的那些）：投到主线程调，等返回值。 */
    private int syncInt(Object svc, String method, Object... args) throws Exception {
        AtomicInteger out = new AtomicInteger(-1);
        CountDownLatch done = new CountDownLatch(1);
        onMain(() -> {
            try {
                out.set(Ref.asInt(ref.call(svc, method, args)));
            } catch (Throwable t) {
                L.e("ExtraSvc " + method, t);
            } finally {
                done.countDown();
            }
        });
        done.await(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        return out.get();
    }

    private Object svc(String key) {
        switch (key) {
            case "msg": return qq.getMsgService();
            case "group": return qq.getGroupService();
            case "profile": return qq.getProfileService();
            case "buddy": return qq.getBuddyService();
            case "richmedia": return qq.getRichMediaService();
            case "search": return qq.getSearchService();
            case "collection": return qq.getCollectionService();
            default: return null;
        }
    }

    private static JSONObject opJson(QQClient.OpResult r) throws Exception {
        return new JSONObject().put("ok", r != null && r.ok())
                .put("code", r == null ? -1 : r.code)
                .put("msg", r == null ? "no result" : r.msg);
    }

    /** 同步返回的 Map 直接转 JSON（字段名是 uin/uid 这类普通串，不需要深度反射）。 */
    @SuppressWarnings("unchecked")
    private static JSONObject mapJson(Object map) throws Exception {
        JSONObject out = new JSONObject();
        if (map instanceof Map) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) map).entrySet())
                out.put(String.valueOf(e.getKey()), e.getValue() == null ? "" : String.valueOf(e.getValue()));
        }
        return out;
    }

}
