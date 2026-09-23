package com.satori.qq.ui;

import android.service.quicksettings.Tile;
import com.satori.qq.guard.GuardCommand;

/** 「知弦守护」磁贴：在保活与暂停之间切换。暂停只停止保护，不关闭 QQ。 */
public final class GuardTileService extends GuardTile {
    @Override String click(GuardCommand.Result current) {
        if (!current.ok) return current.error;
        GuardCommand.Result result = current.armed() ? GuardCommand.pause() : GuardCommand.arm();
        if (!result.ok) return result.error;
        return current.armed() ? "知弦守护已暂停，QQ 保持运行" : "知弦守护已开启";
    }

    @Override void render(Tile tile, GuardCommand.Result status) {
        tile.setState(status.armed() ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        subtitle(tile, status.armed() ? "保活中" : "已暂停");
    }
}
