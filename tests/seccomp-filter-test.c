/* Regression: seccomp classic BPF jumps need BPF_JMP|BPF_JEQ, not BPF_JEQ alone. */
#include <stdio.h>
#include <string.h>
#include <unistd.h>
#include <errno.h>
#include <sys/prctl.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <linux/audit.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <signal.h>
#include <stdint.h>

#ifndef AUDIT_ARCH_AARCH64
#define AUDIT_ARCH_AARCH64 0xC00000B7u
#endif

static int wait_child(pid_t pid, int* st) {
    for (int i = 0; i < 50; i++) {
        if (waitpid(pid, st, WNOHANG) == pid) return 1;
        usleep(20000);
    }
    kill(pid, SIGKILL);
    waitpid(pid, st, 0);
    return 0;
}

static int g_fail;

static void report(const char* name, int waited, int st, int expect_errno) {
    if (!waited) {
        printf("FAIL %s timeout\n", name);
        g_fail = 1;
        return;
    }
    if (WIFEXITED(st)) {
        int e = WEXITSTATUS(st);
        if (expect_errno) {
            if (e == expect_errno) printf("OK %s expected errno=%d\n", name, e);
            else {
                printf("FAIL %s wanted errno=%d got %d\n", name, expect_errno, e);
                g_fail = 1;
            }
            return;
        }
        if (e) {
            printf("FAIL %s errno=%d\n", name, e);
            g_fail = 1;
        } else printf("OK %s install (child exit 0)\n", name);
        return;
    }
    if (WIFSIGNALED(st)) {
        printf("OK %s install then signal=%d\n", name, WTERMSIG(st));
        return;
    }
    printf("FAIL %s status=%d\n", name, st);
    g_fail = 1;
}

static void run_filt(const char* name, struct sock_filter* filt, unsigned short n,
        unsigned flags, int expect_errno) {
    pid_t pid = fork();
    if (pid < 0) {
        printf("FAIL %s fork errno=%d\n", name, errno);
        g_fail = 1;
        return;
    }
    if (pid == 0) {
        if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) != 0) _exit(errno ? errno : 1);
        struct sock_fprog prog;
        memset(&prog, 0, sizeof(prog));
        prog.len = n;
        prog.filter = filt;
        if (syscall(__NR_seccomp, SECCOMP_SET_MODE_FILTER, flags, &prog) == 0) _exit(0);
        _exit(errno ? errno : 1);
    }
    int st = 0;
    int waited = wait_child(pid, &st);
    report(name, waited, st, expect_errno);
}

int main(void) {
    struct sock_filter bad[] = {
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, 0),
        BPF_JUMP(BPF_JEQ | BPF_K, 56, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
    };
    printf("bad_jump_code=%04x (expect 0010 then EINVAL)\n", bad[1].code);
    run_filt("missing_BPF_JMP", bad, 4, 0, 22);

    struct sock_filter good[] = {
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, 0),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, 56, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
    };
    printf("good_jump_code=%04x (expect 0015)\n", good[1].code);
    run_filt("with_BPF_JMP", good, 4, 0, 0);

    uint32_t nrs[] = {56, 48, 439, 79, 78, 291, 437};
    uint32_t lo32 = 0x1000, hi32 = 0x2000, hi4g = 0;
    struct sock_filter v[32];
    int n = 0;
    v[n++] = (struct sock_filter)BPF_STMT(BPF_LD | BPF_W | BPF_ABS, 4);
    v[n++] = (struct sock_filter)BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, AUDIT_ARCH_AARCH64, 1, 0);
    v[n++] = (struct sock_filter)BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW);
    v[n++] = (struct sock_filter)BPF_STMT(BPF_LD | BPF_W | BPF_ABS, 0);
    int nr_base = n;
    for (unsigned i = 0; i < sizeof(nrs) / sizeof(nrs[0]); i++)
        v[n++] = (struct sock_filter)BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, nrs[i], 0, 0);
    v[n++] = (struct sock_filter)BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW);
    v[n++] = (struct sock_filter)BPF_STMT(BPF_LD | BPF_W | BPF_ABS, 12);
    v[n++] = (struct sock_filter)BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, hi4g, 1, 0);
    v[n++] = (struct sock_filter)BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP);
    v[n++] = (struct sock_filter)BPF_STMT(BPF_LD | BPF_W | BPF_ABS, 8);
    v[n++] = (struct sock_filter)BPF_JUMP(BPF_JMP | BPF_JGE | BPF_K, lo32, 1, 0);
    v[n++] = (struct sock_filter)BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP);
    v[n++] = (struct sock_filter)BPF_JUMP(BPF_JMP | BPF_JGE | BPF_K, hi32, 0, 1);
    v[n++] = (struct sock_filter)BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP);
    v[n++] = (struct sock_filter)BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW);
    int nr_count = (int)(sizeof(nrs) / sizeof(nrs[0]));
    for (int i = 0; i < nr_count; i++) {
        int remaining = nr_count - 1 - i;
        v[nr_base + i].jt = (uint8_t)(remaining + 1);
        v[nr_base + i].jf = 0;
    }
    run_filt("v4_filter", v, (unsigned short)n, 0, 0);
    run_filt("v4_filter_tsync", v, (unsigned short)n, SECCOMP_FILTER_FLAG_TSYNC, 0);
    return g_fail;
}
