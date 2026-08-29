import com.satori.qq.packet.GroupFiles;
import com.satori.qq.packet.Pb;

/** Offline wire-format checks for group-file OIDB codecs. */
public final class GroupFilesTest {
    public static void main(String[] args) {
        testRequests();
        testSystemInfo();
        testList();
        testUrl();
        testWrites();
        System.out.println("GroupFilesTest OK");
    }

    private static void testRequests() {
        Pb.Reader count = new Pb.Reader(GroupFiles.countRequest(123)).msg(3);
        eq(123, count.num(1), "count group");
        eq(7, count.num(2), "count app");
        eq(6, count.num(3), "count bus selector");

        Pb.Reader list = new Pb.Reader(GroupFiles.listRequest(123, "/folder", 50, 20)).msg(2);
        eq(123, list.num(1), "list group");
        eq("/folder", list.str(3), "list folder");
        eq(20, list.num(5), "list count");
        eq(50, list.num(13), "list cursor");
        eq(2, list.num(17), "list order");

        Pb.Reader url = new Pb.Reader(GroupFiles.urlRequest(123, "/file", 0)).msg(3);
        eq(102, url.num(3), "default download bus");
        eq("/file", url.str(4), "download file id");

        Pb.Reader create = new Pb.Reader(GroupFiles.createFolderRequest(123, "", "docs")).msg(1);
        eq(123, create.num(1), "create group");
        eq(7, create.num(2), "create app");
        eq("/", create.str(3), "create parent default");
        eq("docs", create.str(4), "create name");

        Pb.Reader deleteFolder = new Pb.Reader(GroupFiles.deleteFolderRequest(123, "/fid")).msg(2);
        eq("/fid", deleteFolder.str(3), "delete folder id");

        Pb.Reader renameFolder = new Pb.Reader(GroupFiles.renameFolderRequest(123, "/fid", "x")).msg(3);
        eq("x", renameFolder.str(4), "rename folder name");

        Pb.Reader deleteFile = new Pb.Reader(GroupFiles.deleteFileRequest(123, "/file", 0)).msg(4);
        eq(102, deleteFile.num(3), "delete file bus");
        eq("/file", deleteFile.str(5), "delete file id");

        Pb.Reader renameFile = new Pb.Reader(GroupFiles.renameFileRequest(123, "/file", "", "a.txt", 0)).msg(5);
        eq("/", renameFile.str(5), "rename parent default");
        eq("a.txt", renameFile.str(6), "rename new name");

        Pb.Reader moveFile = new Pb.Reader(GroupFiles.moveFileRequest(123, "/file", "/", "/dest", 0)).msg(6);
        eq("/file", moveFile.str(4), "move file id");
        eq("/", moveFile.str(5), "move parent");
        eq("/dest", moveFile.str(6), "move dest");
    }

    private static void testSystemInfo() {
        byte[] countBody = Pb.w().message(3, Pb.w().varint(1, 0)
                .varint(4, 12).varint(6, 100).bool(7, false)).toByteArray();
        GroupFiles.CountResult count = GroupFiles.parseCount(countBody);
        eq(0, count.code, "count code");
        eq(12, count.fileCount, "file count");
        eq(100, count.limitCount, "limit count");

        byte[] spaceBody = Pb.w().message(4, Pb.w().varint(1, 0)
                .varint(4, 10_000).varint(5, 1_234).bool(6, true)).toByteArray();
        GroupFiles.SpaceResult space = GroupFiles.parseSpace(spaceBody);
        eq(0, space.code, "space code");
        eq(10_000, space.totalSpace, "total space");
        eq(1_234, space.usedSpace, "used space");
        check(space.allUpload, "allUpload");
    }

    private static void testList() {
        Pb.Writer file = Pb.w().string(1, "/file-id").string(2, "test.txt")
                .varint(3, 42).varint(4, 102).varint(6, 1000).varint(7, 0)
                .varint(8, 1001).varint(9, 3).string(14, "alice").varint(15, 456)
                .string(16, "/");
        Pb.Writer folder = Pb.w().string(1, "/folder-id").string(2, "/")
                .string(3, "docs").varint(4, 900).varint(6, 789)
                .string(7, "bob").varint(8, 2);
        Pb.Writer result = Pb.w().varint(1, 0).bool(4, true)
                .message(5, Pb.w().varint(1, 1).message(3, file))
                .message(5, Pb.w().varint(1, 2).message(2, folder))
                .varint(7, 3).varint(13, 2);
        GroupFiles.ListResult list = GroupFiles.parseList(
                Pb.w().message(2, result).toByteArray());
        eq(0, list.code, "list code");
        check(list.end, "list end");
        eq(2, list.entries.size(), "entry count");
        GroupFiles.Entry fileEntry = list.entries.get(0);
        check(!fileEntry.folder, "file type");
        eq("/file-id", fileEntry.id, "file id");
        eq(42, fileEntry.size, "file size");
        eq(456, fileEntry.uploaderUin, "uploader");
        GroupFiles.Entry folderEntry = list.entries.get(1);
        check(folderEntry.folder, "folder type");
        eq("docs", folderEntry.name, "folder name");
        eq(2, folderEntry.totalFileCount, "folder count");
    }

    private static void testUrl() {
        Pb.Writer download = Pb.w().varint(1, 0).string(5, "example.qq.com")
                .bytes(6, new byte[]{0x01, (byte) 0xab});
        GroupFiles.UrlResult result = GroupFiles.parseUrl(
                Pb.w().message(3, download).toByteArray());
        eq(0, result.code, "url code");
        eq("https://example.qq.com/ftn_handler/01ab/?fname=", result.url, "url");
    }

    private static void testWrites() {
        byte[] createBody = Pb.w().message(1, Pb.w().varint(1, 0)
                .message(4, Pb.w().string(1, "/fid").string(2, "/").string(3, "docs")
                        .varint(4, 9).varint(6, 1).string(7, "alice"))).toByteArray();
        GroupFiles.OpResult created = GroupFiles.parseCreateFolder(createBody);
        eq(0, created.code, "create code");
        check(created.folder != null, "create folder present");
        eq("/fid", created.folder.id, "create folder id");
        eq("docs", created.folder.name, "create folder name");

        GroupFiles.OpResult deleted = GroupFiles.parseDeleteFolder(
                Pb.w().message(2, Pb.w().varint(1, 0).string(3, "ok")).toByteArray());
        eq(0, deleted.code, "delete folder code");

        GroupFiles.OpResult renamed = GroupFiles.parseRenameFolder(
                Pb.w().message(3, Pb.w().varint(1, 12).string(3, "busy")).toByteArray());
        eq(12, renamed.code, "rename folder error code");
        eq("busy", renamed.message, "rename folder wording");

        GroupFiles.OpResult fileDeleted = GroupFiles.parseDeleteFile(
                Pb.w().message(4, Pb.w().varint(1, 0)).toByteArray());
        eq(0, fileDeleted.code, "delete file code");
        GroupFiles.OpResult moved = GroupFiles.parseMoveFile(
                Pb.w().message(6, Pb.w().varint(1, 0)).toByteArray());
        eq(0, moved.code, "move file code");
    }

    private static void check(boolean value, String name) {
        if (!value) throw new AssertionError(name);
    }

    private static void eq(long expected, long actual, String name) {
        if (expected != actual) throw new AssertionError(name + ": " + actual + " != " + expected);
    }

    private static void eq(String expected, String actual, String name) {
        if (!expected.equals(actual)) throw new AssertionError(name + ": " + actual + " != " + expected);
    }
}
