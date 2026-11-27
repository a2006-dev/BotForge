cmake_minimum_required(VERSION 3.22.1)

project(bfpty C)

# 只产出一个 libbfpty.so，里面装 forkpty 相关的三个 JNI 函数。
# 不链其它库：forkpty 在 bionic 的 libc 里，sys/wait、termios 也是。
add_library(bfpty SHARED pty.c)

# 链接 liblog，供 __android_log_print 使用。
find_library(log-lib log)
target_link_libraries(bfpty ${log-lib})
