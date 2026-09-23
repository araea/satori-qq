package com.satori.qq.ui;

import android.service.quicksettings.Tile;
import com.satori.qq.guard.GuardCommand;

/**
 * 「停止保活并关闭 QQ」磁贴：先暂停看守，再强停 QQ。之后不会被自动拉起，
 * 想恢复就点「知弦守护」磁贴或在应用里重新开启。
 */
public final class GuardStopTileService extends GuardTile {
    @Override String click(GuardCommand.Result current) {
        GuardCommand.Result result = GuardCommand.stopAndKill();
        return result.ok ? "已停止保活并关闭 QQ" : result.error;
    }

    @Override void render(Tile tile, GuardCommand.Result status) {
        // 动作型磁贴：保活中可用；已暂停时显示为未激活，但仍可点（QQ 可能是被手动打开的）。
        tile.setState(status.armed() ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        subtitle(tile, status.armed() ? "点按后暂停并关闭 QQ" : "守护已暂停");
    }
}
