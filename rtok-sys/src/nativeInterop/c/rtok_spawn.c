/* rtok-sys native helpers, built into librtok_spawn.a (experiment/binary-split, docs/experiments/binary-split.md)
   and linked through cinterop `staticLibraries` instead of being compiled as inline functions of spawn.def. */
#define _GNU_SOURCE
#include <unistd.h>
#include "rtok_spawn.h"
extern char **environ;
int rtok_spawn_addchdir(posix_spawn_file_actions_t *actions, const char *dir) {
    return posix_spawn_file_actions_addchdir_np(actions, dir);
}
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
    int e = errno;
    close(in);
    if (close(out) != 0 && rc >= 0) { e = errno; rc = -1; }
    errno = e;
    return rc;
}
#endif
