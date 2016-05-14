package com.novemberain.quartz.mongodb.cluster;

import com.novemberain.quartz.mongodb.LockManager;
import com.novemberain.quartz.mongodb.TriggerAndJobPersister;
import com.novemberain.quartz.mongodb.dao.JobDao;
import com.novemberain.quartz.mongodb.dao.LocksDao;
import com.novemberain.quartz.mongodb.dao.TriggerDao;
import org.quartz.JobPersistenceException;
import org.quartz.TriggerKey;
import org.quartz.spi.OperableTrigger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TriggerRecoverer {

    private static final Logger log = LoggerFactory.getLogger(TriggerRecoverer.class);

    private final LocksDao locksDao;
    private final TriggerAndJobPersister persister;
    private final LockManager lockManager;
    private final TriggerDao triggerDao;
    private final JobDao jobDao;
    private final RecoveryTriggerFactory recoveryTriggerFactory;

    public TriggerRecoverer(LocksDao locksDao, TriggerAndJobPersister persister,
                            LockManager lockManager, TriggerDao triggerDao,
                            JobDao jobDao, RecoveryTriggerFactory recoveryTriggerFactory) {
        this.locksDao = locksDao;
        this.persister = persister;
        this.lockManager = lockManager;
        this.triggerDao = triggerDao;
        this.jobDao = jobDao;
        this.recoveryTriggerFactory = recoveryTriggerFactory;
    }

    public void recover() throws JobPersistenceException {
        for (TriggerKey key : locksDao.findOwnTriggersLocks()) {
            OperableTrigger trigger = triggerDao.getTrigger(key);
            if (trigger == null) {
                continue;
            }
            if (jobDao.requestsRecovery(trigger.getJobKey())) {
                recoverTrigger(trigger);
            } else if (wasOneShotTrigger(trigger)) {
                cleanUpFailedRun(trigger);
            }
        }
    }

    private void recoverTrigger(OperableTrigger trigger)
            throws JobPersistenceException {
        log.info("Recovering trigger: {}", trigger.getKey());
        if (locksDao.updateOwnLock(trigger.getKey())) {
            OperableTrigger recoveryTrigger = recoveryTriggerFactory.from(trigger);
            persister.storeTrigger(recoveryTrigger, false);
            locksDao.unlockTrigger(trigger);
        }
    }

    private void cleanUpFailedRun(OperableTrigger trigger) throws JobPersistenceException {
        // Make the trigger's lock fresh for other nodes,
        // so they don't recover it. Also, we don't want to
        // refresh all own locks, because dead jobs should
        // be recovered when acquiring next triggers.
        if (locksDao.updateOwnLock(trigger.getKey())) {
            persister.removeTrigger(trigger.getKey());
            lockManager.unlockAcquiredTrigger(trigger);
        }
    }

    private boolean wasOneShotTrigger(OperableTrigger trigger) {
        return trigger.getNextFireTime() == null;
    }
}