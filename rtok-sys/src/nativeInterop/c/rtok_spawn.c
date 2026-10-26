/* rtok-sys native helpers, built into librtok_spawn.a (experiment/binary-split, docs/experiments/binary-split.md)
   and linked through cinterop `staticLibraries` instead of being compiled as inline functions of spawn.def. */
#include <unistd.h>
#include "rtok_spawn.h"
extern char **environ;
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
