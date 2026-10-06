package com.satori.qq.core;

public final class LoginSelectorTest {
    public static void main(String[] args) {
        long self = 3373167460L;
        check(!Identity.isForeignLogin(null, self), "missing selector is not another login");
        check(!Identity.isForeignLogin("", self), "empty selector is not another login");
        // 0 is the placeholder READY handed out before QQ's account was known. A client that
        // connected then echoes it back forever, so refusing it would leave that client mute.
        check(!Identity.isForeignLogin("0", self), "placeholder selector is not another login");
        check(!Identity.isForeignLogin("3373167460", self), "our own login is accepted");
        check(Identity.isForeignLogin("10001", self), "a different login is refused");
        check(!Identity.isForeignLogin("10001", 0), "an unknown account cannot refute a selector");
        System.out.println("LoginSelectorTest OK");
    }

    private static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
    }
}
