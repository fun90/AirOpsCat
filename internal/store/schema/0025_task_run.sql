-- 周期不短于 1 小时的定时任务的上次开始时间；进程重启后据此续排，避免频繁重启使每日任务一直推迟。
CREATE TABLE task_run (
    task_key   TEXT PRIMARY KEY,
    last_start TEXT NOT NULL
);
