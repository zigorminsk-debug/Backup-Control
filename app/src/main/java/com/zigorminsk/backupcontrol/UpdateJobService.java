package com.zigorminsk.backupcontrol;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.util.Log;

public final class UpdateJobService extends JobService {
    private static final int JOB_ID = 4126;

    static void ensureScheduled(Context context) {
        try {
            JobScheduler scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            for (JobInfo job : scheduler.getAllPendingJobs()) if (job.getId() == JOB_ID) return;
            JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(context, UpdateJobService.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPersisted(true)
                    .setPeriodic(UpdateManager.INTERVAL_MS)
                    .build();
            scheduler.schedule(job);
        } catch (RuntimeException e) {
            // A device's scheduler can be unavailable or have exhausted its job
            // quota. Never block the checklist; the next launch retries scheduling.
            Log.w("BackupControl", "Could not schedule update checks", e);
        }
    }

    @Override public boolean onStartJob(JobParameters params) {
        UpdateManager.checkAsync(this, false, message -> jobFinished(params, false));
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) { return true; }
}
