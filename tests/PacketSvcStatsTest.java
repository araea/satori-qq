import com.satori.qq.packet.PacketSvc;

/**
 * 离线检查「模块自己的 SSO 请求有没有撞上会话类错误码」这条观测口径。
 *
 * <p>被踢下线有两种性质不同的成因：设备环境被检测出来，或者接口调用把会话打废。QQ 只在少数
 * 几个错误码上做「票据失效 → 踢回登录页」，判定表就在 {@link PacketSvc#sessionFamilyCode}。
 * 这里钉住这张表：漏一个码，踢线成因就分不出来；多认一个码，会把正常的业务失败误报成会话失效。
 */
public final class PacketSvcStatsTest {
    public static void main(String[] args) {
        // login.ntlogin.ao.f(int, String) 的三个码。
        check(PacketSvc.sessionFamilyCode(140022014, 0), "140022014");
        check(PacketSvc.sessionFamilyCode(0, 140022015), "140022015 via trpc");
        check(PacketSvc.sessionFamilyCode(140022016, 0), "140022016");
        // MainService$MyErrorHandler.onUserTokenExpired 的 ssoErrorCode。
        check(PacketSvc.sessionFamilyCode(-10135, 0), "-10135");
        check(PacketSvc.sessionFamilyCode(-10136, 0), "-10136");
        check(PacketSvc.sessionFamilyCode(10136, 0), "10136");
        // 正常的业务失败不是会话失效：群不存在、没权限、频率限制这些不该算进来。
        check(!PacketSvc.sessionFamilyCode(0, 0), "success");
        check(!PacketSvc.sessionFamilyCode(1, 0), "business failure");
        check(!PacketSvc.sessionFamilyCode(-10000, 0), "other transport error");
        check(!PacketSvc.sessionFamilyCode(140022017, 0), "neighbouring login code");
        System.out.println("PacketSvcStatsTest OK");
    }

    private static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
    }
}
