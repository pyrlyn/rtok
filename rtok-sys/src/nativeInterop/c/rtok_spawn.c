/* rtok-sys native helpers, built into librtok_spawn.a (experiment/binary-split, docs/experiments/binary-split.md)
   and linked through cinterop `staticLibraries` instead of being compiled as inline functions of spawn.def. */
#define _GNU_SOURCE
#include <unistd.h>
#include "rtok_spawn.h"
#include <errno.h>
extern char **environ;
#ifndef __APPLE__
/* Kotlin/Native links Linux binaries against an old glibc sysroot (2.19 on x64) whose headers have neither
   posix_spawn_file_actions_addchdir_np (glibc 2.29) nor POSIX_SPAWN_SETSID (glibc 2.26). Both are resolved
   against the running glibc: the chdir action weakly (ENOSYS when absent), the flag by its glibc value
   (an older glibc rejects it with EINVAL). */
extern int posix_spawn_file_actions_addchdir_np(posix_spawn_file_actions_t *, const char *) __attribute__((weak));
#ifndef POSIX_SPAWN_SETSID
#define POSIX_SPAWN_SETSID 0x80
#endif
#endif
static int rtok_addchdir(posix_spawn_file_actions_t *actions, const char *dir) {
#ifndef __APPLE__
    if (!posix_spawn_file_actions_addchdir_np) return ENOSYS;
#endif
    return posix_spawn_file_actions_addchdir_np(actions, dir);
}
int rtok_spawn_addchdir(posix_spawn_file_actions_t *actions, const char *dir) { return rtok_addchdir(actions, dir); }
char **rtok_environ(void) { return environ; }
#ifdef __APPLE__
#include <libproc.h>
#include <sys/proc_info.h>
#include <mach-o/dyld.h>
int rtok_parent_of(int pid) {
    struct proc_bsdinfo info;
    int size = (int)sizeof(info);
    if (pid <= 0 || proc_pidinfo(pid, PROC_PIDTBSDINFO, 0, &info, size) != size) return -1;
    return (int)info.pbi_ppid;
}
int rtok_current_exe(char *buf, unsigned int size) { uint32_t s = size; return _NSGetExecutablePath(buf, &s); }
#else
int rtok_parent_of(int pid) { (void)pid; return -1; }
int rtok_current_exe(char *buf, unsigned int size) { (void)buf; (void)size; return -1; }
#endif

#include <errno.h>
#include <fcntl.h>
#include <sys/stat.h>
#ifdef __APPLE__
#include <copyfile.h>
#include <sys/clonefile.h>
int rtok_clone_or_copy(const char *from, const char *to) {
    if (clonefile(from, to, CLONE_NOFOLLOW) == 0) return 1;
    if (errno != ENOTSUP && errno != EXDEV) return -1;
    return copyfile(from, to, NULL, COPYFILE_DATA | COPYFILE_STAT | COPYFILE_EXCL | COPYFILE_NOFOLLOW) == 0 ? 0 : -1;
}
#else
#include <sys/ioctl.h>
#include <linux/fs.h>
int rtok_clone_or_copy(const char *from, const char *to) {
    int in = open(from, O_RDONLY | O_NOFOLLOW);
    if (in < 0) return -1;
    struct stat st;
    if (fstat(in, &st) != 0) { int e = errno; close(in); errno = e; return -1; }
    int out = open(to, O_WRONLY | O_CREAT | O_EXCL, st.st_mode & 07777);
    if (out < 0) { int e = errno; close(in); errno = e; return -1; }
    int rc = 1;
    if (ioctl(out, FICLONE, in) != 0) {
        char buf[65536];
        ssize_t n;
        rc = 0;
        while ((n = read(in, buf, sizeof buf)) > 0) {
            for (ssize_t off = 0; off < n;) {
                ssize_t w = write(out, buf + off, (size_t)(n - off));
                if (w < 0) { rc = -1; break; }
                off += w;
            }
            if (rc < 0) break;
        }
        if (n < 0) rc = -1;
    }
    /* Keep the source's access and modification times, as clonefile/copyfile(COPYFILE_STAT) do on macOS. */
    if (rc >= 0) {
        struct timespec times[2] = {st.st_atim, st.st_mtim};
        if (futimens(out, times) != 0) rc = -1;
    }
    int e = errno;
    close(in);
    if (close(out) != 0 && rc >= 0) { e = errno; rc = -1; }
    errno = e;
    return rc;
}
#endif

#include <sys/statvfs.h>
#include <time.h>
int rtok_spawn(int *pid, const char *prog, char *const argv[], char *const envp[], int n, const int *kind,
               const int *fd, const int *arg, const int *mode, const char *const *path, int setsid, int search) {
    posix_spawn_file_actions_t actions;
    posix_spawnattr_t attr;
    int rc = posix_spawn_file_actions_init(&actions);
    if (rc != 0) return rc;
    for (int i = 0; i < n && rc == 0; i++) {
        switch (kind[i]) {
        case 0: rc = posix_spawn_file_actions_adddup2(&actions, arg[i], fd[i]); break;
        case 1: rc = posix_spawn_file_actions_addopen(&actions, fd[i], path[i], arg[i], (mode_t)mode[i]); break;
        case 2: rc = posix_spawn_file_actions_addclose(&actions, fd[i]); break;
        case 3: rc = rtok_addchdir(&actions, path[i]); break;
        default: rc = EINVAL;
        }
    }
    if (rc == 0) rc = posix_spawnattr_init(&attr);
    else { posix_spawn_file_actions_destroy(&actions); return rc; }
    if (rc == 0 && setsid) rc = posix_spawnattr_setflags(&attr, POSIX_SPAWN_SETSID);
    pid_t child = 0;
    if (rc == 0) rc = search ? posix_spawnp(&child, prog, &actions, &attr, argv, envp)
                             : posix_spawn(&child, prog, &actions, &attr, argv, envp);
    posix_spawnattr_destroy(&attr);
    posix_spawn_file_actions_destroy(&actions);
    if (rc == 0) *pid = (int)child;
    return rc;
}
long long rtok_free_bytes(const char *path) {
    struct statvfs st;
    if (statvfs(path, &st) != 0) return -1;
    return (long long)st.f_bavail * (long long)st.f_frsize;
}
int rtok_set_times(const char *path, long long sec, long nsec) {
    struct timespec t[2];
    for (int i = 0; i < 2; i++) { t[i].tv_sec = (time_t)sec; t[i].tv_nsec = nsec; }
    return utimensat(AT_FDCWD, path, t, 0);
}
