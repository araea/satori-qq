import com.satori.qq.Cfg;
import com.satori.qq.core.MsgStore;
import com.satori.qq.core.SatoriHub;
import com.satori.qq.net.HttpServer;
import com.satori.qq.qq.QQClient;
import java.lang.reflect.Field;

/** 在真 ART 上起一个离线的 SatoriHub（没有 QQ 内核），给黑盒探针打。 */
public class Target {
    public static void main(String[] args) throws Exception {
        QQClient qq = new QQClient(Target.class.getClassLoader(), false);
        Field self = QQClient.class.getDeclaredField("selfUin");
        self.setAccessible(true);
        self.set(qq, "10001");
        Cfg cfg = new Cfg();
        cfg.port = Integer.parseInt(args[0]);
        cfg.token = "s3cret";
        SatoriHub hub = new SatoriHub(cfg, qq, new MsgStore());
        new HttpServer(cfg, hub).start();
        System.out.println("target up on " + cfg.port);
        Thread.sleep(Long.parseLong(args[1]) * 1000L);
    }
}
