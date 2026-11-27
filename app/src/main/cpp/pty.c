// BotForge 的 PTY 原生实现。
//
// 为什么必须走 JNI：
//   Java 层的 ProcessBuilder 只能用管道连子进程，管道不是终端设备，
//   isatty() 返回 false。bash 一启动就发现 stdin 是管道并立刻读到 EOF，
//   打印完提示符就 logout 退出。表现就是「会话闪一下就没了、输入没反应」。
//   之前试过用 rootfs 里的 script 命令包一层（它内部也调 posix_openpt），
//   但 script 自己又要接一个 stdin，在 App 的管道上下文里同样撑不住。
//   唯一干净的路是自己 forkpty，让子进程直接挂在一个真终端上。
//
// 实现方式参考 Operit 的 pty.c（AAswordman/OperitTerminalCore），按本项目
// 需要裁剪到最小集：建子进程、等退出、设窗口大小。

#include <jni.h>
#include <string.h>
#include <unistd.h>
#include <stdlib.h>
#include <fcntl.h>
#include <sys/wait.h>
#include <sys/ioctl.h>
#include <errno.h>
#include <stdio.h>
#include <android/log.h>
#include <termios.h>
#ifdef __linux__
#include <pty.h>
#endif

#define TAG "BfPty"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)

// 返回 jintArray{pid, masterFd}，失败返回 NULL。
JNIEXPORT jintArray JNICALL
Java_com_ajx_botforge_term_Pty_00024Companion_createSubprocess(
        JNIEnv *env, jobject thiz,
        jobjectArray cmdarray, jobjectArray envarray, jstring workingDir) {

    int master_fd = -1;
    pid_t pid;

    const char *cwd_jni = (*env)->GetStringUTFChars(env, workingDir, 0);
    char *cwd = strdup(cwd_jni);
    (*env)->ReleaseStringUTFChars(env, workingDir, cwd_jni);

    // ===== 环境变量 =====
    int env_len = (*env)->GetArrayLength(env, envarray);
    char **envp = (char **) malloc(sizeof(char *) * (env_len + 1));
    for (int i = 0; i < env_len; i++) {
        jstring j_env = (jstring) (*env)->GetObjectArrayElement(env, envarray, i);
        const char *s = (*env)->GetStringUTFChars(env, j_env, 0);
        envp[i] = strdup(s);
        (*env)->ReleaseStringUTFChars(env, j_env, s);
        (*env)->DeleteLocalRef(env, j_env);
    }
    envp[env_len] = NULL;

    // ===== 参数数组 =====
    int cmd_len = (*env)->GetArrayLength(env, cmdarray);
    char **argv = (char **) malloc(sizeof(char *) * (cmd_len + 1));
    for (int i = 0; i < cmd_len; i++) {
        jstring j_cmd = (jstring) (*env)->GetObjectArrayElement(env, cmdarray, i);
        const char *s = (*env)->GetStringUTFChars(env, j_cmd, 0);
        argv[i] = strdup(s);
        (*env)->ReleaseStringUTFChars(env, j_cmd, s);
        (*env)->DeleteLocalRef(env, j_cmd);
    }
    argv[cmd_len] = NULL;

    // ===== 终端属性 =====
    // 这里刻意把 ICANON 打开、ECHO 打开，让 PTY 处于最常见的行缓冲 + 回显
    // 状态。VINTR 设成 Ctrl+C 的 0x03，这样在界面上发 \u0003 就能触发 SIGINT，
    // 而不用像管道那样只能杀掉整个进程。
    struct termios tt;
    memset(&tt, 0, sizeof(tt));
    tt.c_iflag = ICRNL | IXON | IXANY;
    tt.c_oflag = OPOST | ONLCR;
    tt.c_lflag = ISIG | ICANON | ECHO | ECHOE | ECHOK | ECHONL | IEXTEN;
    tt.c_cflag = CS8 | CREAD;
    tt.c_cc[VINTR] = 'C' - '@';
    tt.c_cc[VQUIT] = '\\' - '@';
    tt.c_cc[VERASE] = 0x7f;
    tt.c_cc[VKILL] = 'U' - '@';
    tt.c_cc[VEOF] = 'D' - '@';
    tt.c_cc[VSTOP] = 'S' - '@';
    tt.c_cc[VSUSP] = 'Z' - '@';
    tt.c_cc[VSTART] = 'Q' - '@';
    tt.c_cc[VMIN] = 1;
    tt.c_cc[VTIME] = 0;

    struct winsize ws;
    ws.ws_row = 40;
    ws.ws_col = 100;
    ws.ws_xpixel = 0;
    ws.ws_ypixel = 0;

    pid = forkpty(&master_fd, NULL, &tt, &ws);
    if (pid < 0) {
        LOGE("forkpty failed: %s", strerror(errno));
        free(cwd);
        for (int i = 0; i < cmd_len; i++) free(argv[i]);
        free(argv);
        for (int i = 0; i < env_len; i++) free(envp[i]);
        free(envp);
        return NULL;
    }

    if (pid == 0) {
        // 子进程：进入工作目录后 exec。execve 成功不会返回。
        if (chdir(cwd) != 0) {
            fprintf(stderr, "chdir to %s failed: %s\n", cwd, strerror(errno));
            _exit(1);
        }
        execve(argv[0], argv, envp);
        fprintf(stderr, "execve(%s) failed: %s\n", argv[0], strerror(errno));
        _exit(1);
    }

    // 父进程：把 {pid, master_fd} 交给 Kotlin。
    jintArray result = (*env)->NewIntArray(env, 2);
    jint pair[2];
    pair[0] = (jint) pid;
    pair[1] = (jint) master_fd;
    (*env)->SetIntArrayRegion(env, result, 0, 2, pair);

    free(cwd);
    for (int i = 0; i < cmd_len; i++) free(argv[i]);
    free(argv);
    for (int i = 0; i < env_len; i++) free(envp[i]);
    free(envp);

    LOGD("forkpty ok: pid=%d fd=%d", pid, master_fd);
    return result;
}

JNIEXPORT jint JNICALL
Java_com_ajx_botforge_term_Pty_00024Companion_waitForPid(JNIEnv *env, jobject thiz, jint pid) {
    int status = 0;
    int rc;
    do {
        rc = waitpid((pid_t) pid, &status, 0);
    } while (rc < 0 && errno == EINTR);

    if (rc < 0) return -1;
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
    return -1;
}

JNIEXPORT jint JNICALL
Java_com_ajx_botforge_term_Pty_00024Companion_setPtyWindowSize(
        JNIEnv *env, jobject thiz, jint fd, jint rows, jint cols) {
    struct winsize ws;
    ws.ws_row = (unsigned short) rows;
    ws.ws_col = (unsigned short) cols;
    ws.ws_xpixel = 0;
    ws.ws_ypixel = 0;
    if (ioctl(fd, TIOCSWINSZ, &ws) != 0) {
        LOGE("TIOCSWINSZ failed for fd %d: %s", fd, strerror(errno));
        return -1;
    }
    return 0;
}
