#ifndef RTOK_SPAWN_H
#define RTOK_SPAWN_H
char **rtok_environ(void);
/* Parent pid of `pid` via proc_pidinfo(PROC_PIDTBSDINFO), or -1 (Rust rtok_sys::parent_of, macOS arm). */
int rtok_parent_of(int pid);
/* Path of the running executable into buf; 0 on success (Rust std::env::current_exe). */
int rtok_current_exe(char *buf, unsigned int size);
#endif
