#ifndef RTOK_SPAWN_H
#define RTOK_SPAWN_H
char **rtok_environ(void);
/* Parent pid of `pid` via proc_pidinfo(PROC_PIDTBSDINFO), or -1 (Rust rtok_sys::parent_of, macOS arm). */
int rtok_parent_of(int pid);
/* Path of the running executable into buf; 0 on success (Rust std::env::current_exe). */
int rtok_current_exe(char *buf, unsigned int size);
#include <spawn.h>
/* posix_spawn_file_actions_addchdir_np (macOS 10.15+, glibc 2.29+): the child starts in `dir`; 0 on success. */
int rtok_spawn_addchdir(posix_spawn_file_actions_t *actions, const char *dir);
/* Copy-on-write clone of the regular file `from` to the new path `to` where the volume can make one (APFS clonefile,
   Linux FICLONE), else a full copy: 1 cloned, 0 copied, -1 with errno set (reflink_copy::reflink_or_copy). */
int rtok_clone_or_copy(const char *from, const char *to);
/* posix_spawn(p) with an ordered list of `n` file actions, so Kotlin code never names the posix_spawn types (on Linux
   they live in platform.linux, which shared native code cannot import). kind[i]: 0 dup2(arg[i] -> fd[i]),
   1 open(path[i], arg[i] flags, mode[i]) as fd[i], 2 close(fd[i]), 3 chdir(path[i]). `setsid` starts a new session,
   `search` looks the program up on PATH. 0 with *pid set, else the errno. */
int rtok_spawn(int *pid, const char *prog, char *const argv[], char *const envp[], int n, const int *kind,
               const int *fd, const int *arg, const int *mode, const char *const *path, int setsid, int search);
/* statvfs f_bavail * f_frsize of the volume holding `path`, or -1 with errno set (rtok_sys::free_bytes). */
long long rtok_free_bytes(const char *path);
/* utimensat(AT_FDCWD, path) setting access and modification time to sec/nsec; 0, else -1 with errno set. */
int rtok_set_times(const char *path, long long sec, long nsec);
#endif
