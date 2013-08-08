package com.twitter.aurora.scheduler.thrift;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.annotation.Nullable;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.base.Throwables;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multimaps;
import com.google.common.collect.Sets;
import com.google.inject.Inject;

import org.apache.commons.lang.StringUtils;

import com.twitter.aurora.auth.SessionValidator.AuthFailedException;
import com.twitter.aurora.gen.AssignedTask;
import com.twitter.aurora.gen.AuroraAdmin;
import com.twitter.aurora.gen.CommitRecoveryResponse;
import com.twitter.aurora.gen.ConfigRewrite;
import com.twitter.aurora.gen.CreateJobResponse;
import com.twitter.aurora.gen.DeleteRecoveryTasksResponse;
import com.twitter.aurora.gen.DrainHostsResponse;
import com.twitter.aurora.gen.EndMaintenanceResponse;
import com.twitter.aurora.gen.FinishUpdateResponse;
import com.twitter.aurora.gen.ForceTaskStateResponse;
import com.twitter.aurora.gen.GetJobUpdatesResponse;
import com.twitter.aurora.gen.GetJobsResponse;
import com.twitter.aurora.gen.GetQuotaResponse;
import com.twitter.aurora.gen.Hosts;
import com.twitter.aurora.gen.JobConfigRewrite;
import com.twitter.aurora.gen.JobConfiguration;
import com.twitter.aurora.gen.JobKey;
import com.twitter.aurora.gen.JobUpdateConfiguration;
import com.twitter.aurora.gen.KillResponse;
import com.twitter.aurora.gen.ListBackupsResponse;
import com.twitter.aurora.gen.MaintenanceStatusResponse;
import com.twitter.aurora.gen.PerformBackupResponse;
import com.twitter.aurora.gen.PopulateJobResponse;
import com.twitter.aurora.gen.QueryRecoveryResponse;
import com.twitter.aurora.gen.Quota;
import com.twitter.aurora.gen.ResponseCode;
import com.twitter.aurora.gen.RestartShardsResponse;
import com.twitter.aurora.gen.RewriteConfigsRequest;
import com.twitter.aurora.gen.RewriteConfigsResponse;
import com.twitter.aurora.gen.RollbackShardsResponse;
import com.twitter.aurora.gen.ScheduleStatus;
import com.twitter.aurora.gen.ScheduleStatusResponse;
import com.twitter.aurora.gen.ScheduledTask;
import com.twitter.aurora.gen.SessionKey;
import com.twitter.aurora.gen.SetQuotaResponse;
import com.twitter.aurora.gen.ShardConfigRewrite;
import com.twitter.aurora.gen.ShardKey;
import com.twitter.aurora.gen.SnapshotResponse;
import com.twitter.aurora.gen.StageRecoveryResponse;
import com.twitter.aurora.gen.StartCronResponse;
import com.twitter.aurora.gen.StartMaintenanceResponse;
import com.twitter.aurora.gen.StartUpdateResponse;
import com.twitter.aurora.gen.TaskConfig;
import com.twitter.aurora.gen.TaskQuery;
import com.twitter.aurora.gen.UnloadRecoveryResponse;
import com.twitter.aurora.gen.UpdateResponseCode;
import com.twitter.aurora.gen.UpdateResult;
import com.twitter.aurora.gen.UpdateShardsResponse;
import com.twitter.aurora.scheduler.base.JobKeys;
import com.twitter.aurora.scheduler.base.Query;
import com.twitter.aurora.scheduler.base.ScheduleException;
import com.twitter.aurora.scheduler.base.Tasks;
import com.twitter.aurora.scheduler.configuration.ConfigurationManager;
import com.twitter.aurora.scheduler.configuration.ConfigurationManager.TaskDescriptionException;
import com.twitter.aurora.scheduler.configuration.ParsedConfiguration;
import com.twitter.aurora.scheduler.quota.QuotaManager;
import com.twitter.aurora.scheduler.state.CronJobManager;
import com.twitter.aurora.scheduler.state.MaintenanceController;
import com.twitter.aurora.scheduler.state.SchedulerCore;
import com.twitter.aurora.scheduler.storage.JobStore;
import com.twitter.aurora.scheduler.storage.Storage;
import com.twitter.aurora.scheduler.storage.Storage.MutableStoreProvider;
import com.twitter.aurora.scheduler.storage.Storage.MutateWork;
import com.twitter.aurora.scheduler.storage.Storage.StoreProvider;
import com.twitter.aurora.scheduler.storage.Storage.Work;
import com.twitter.aurora.scheduler.storage.UpdateStore;
import com.twitter.aurora.scheduler.storage.backup.Recovery;
import com.twitter.aurora.scheduler.storage.backup.Recovery.RecoveryException;
import com.twitter.aurora.scheduler.storage.backup.StorageBackup;
import com.twitter.aurora.scheduler.thrift.auth.CapabilityValidator;
import com.twitter.aurora.scheduler.thrift.auth.CapabilityValidator.Capability;
import com.twitter.aurora.scheduler.thrift.auth.DecoratedThrift;
import com.twitter.aurora.scheduler.thrift.auth.Requires;
import com.twitter.common.args.Arg;
import com.twitter.common.args.CmdLine;
import com.twitter.common.base.MorePreconditions;
import com.twitter.common.base.Supplier;
import com.twitter.common.quantity.Amount;
import com.twitter.common.quantity.Time;
import com.twitter.common.util.BackoffHelper;

import static com.google.common.base.Preconditions.checkNotNull;

import static com.twitter.aurora.gen.ResponseCode.AUTH_FAILED;
import static com.twitter.aurora.gen.ResponseCode.ERROR;
import static com.twitter.aurora.gen.ResponseCode.INVALID_REQUEST;
import static com.twitter.aurora.gen.ResponseCode.OK;
import static com.twitter.common.base.MorePreconditions.checkNotBlank;

/**
 * Aurora scheduler thrift server implementation.
 * <p>
 * Interfaces between users and the scheduler to access/modify jobs and perform cluster
 * administration tasks.
 */
@DecoratedThrift
class SchedulerThriftInterface implements AuroraAdmin.Iface {
  private static final Logger LOG = Logger.getLogger(SchedulerThriftInterface.class.getName());

  @CmdLine(name = "kill_task_initial_backoff",
      help = "Initial backoff delay while waiting for the tasks to transition to KILLED.")
  private static final Arg<Amount<Long, Time>> KILL_TASK_INITIAL_BACKOFF =
      Arg.create(Amount.of(1L, Time.SECONDS));

  @CmdLine(name = "kill_task_max_backoff",
      help = "Max backoff delay while waiting for the tasks to transition to KILLED.")
  private static final Arg<Amount<Long, Time>> KILL_TASK_MAX_BACKOFF =
      Arg.create(Amount.of(30L, Time.SECONDS));

  private static final Function<ScheduledTask, String> GET_ROLE = Functions.compose(
      new Function<TaskConfig, String>() {
        @Override public String apply(TaskConfig task) {
          return task.getOwner().getRole();
        }
      },
      Tasks.SCHEDULED_TO_INFO);

  private final Storage storage;
  private final SchedulerCore schedulerCore;
  private final CapabilityValidator sessionValidator;
  private final QuotaManager quotaManager;
  private final StorageBackup backup;
  private final Recovery recovery;
  private final MaintenanceController maintenance;
  private final CronJobManager cronJobManager;
  private final Amount<Long, Time> killTaskInitialBackoff;
  private final Amount<Long, Time> killTaskMaxBackoff;

  @Inject
  SchedulerThriftInterface(
      Storage storage,
      SchedulerCore schedulerCore,
      CapabilityValidator sessionValidator,
      QuotaManager quotaManager,
      StorageBackup backup,
      Recovery recovery,
      CronJobManager cronJobManager,
      MaintenanceController maintenance) {

    this(storage,
        schedulerCore,
        sessionValidator,
        quotaManager,
        backup,
        recovery,
        maintenance,
        cronJobManager,
        KILL_TASK_INITIAL_BACKOFF.get(),
        KILL_TASK_MAX_BACKOFF.get());
  }

  @VisibleForTesting
  SchedulerThriftInterface(
      Storage storage,
      SchedulerCore schedulerCore,
      CapabilityValidator sessionValidator,
      QuotaManager quotaManager,
      StorageBackup backup,
      Recovery recovery,
      MaintenanceController maintenance,
      CronJobManager cronJobManager,
      Amount<Long, Time> initialBackoff,
      Amount<Long, Time> maxBackoff) {

    this.storage = checkNotNull(storage);
    this.schedulerCore = checkNotNull(schedulerCore);
    this.sessionValidator = checkNotNull(sessionValidator);
    this.quotaManager = checkNotNull(quotaManager);
    this.backup = checkNotNull(backup);
    this.recovery = checkNotNull(recovery);
    this.maintenance = checkNotNull(maintenance);
    this.cronJobManager = checkNotNull(cronJobManager);
    this.killTaskInitialBackoff = checkNotNull(initialBackoff);
    this.killTaskMaxBackoff = checkNotNull(maxBackoff);
  }

  @Override
  public CreateJobResponse createJob(JobConfiguration job, SessionKey session) {
    checkNotNull(job);
    checkNotNull(session);

    CreateJobResponse response = new CreateJobResponse();
    if (!JobKeys.isValid(job.getKey())) {
      return response.setResponseCode(INVALID_REQUEST)
          .setMessage("Invalid job key: " + job.getKey());
    }

    try {
      sessionValidator.checkAuthenticated(session, job.getOwner().getRole());
    } catch (AuthFailedException e) {
      return response.setResponseCode(AUTH_FAILED).setMessage(e.getMessage());
    }

    try {
      ParsedConfiguration parsed = ParsedConfiguration.fromUnparsed(job);
      schedulerCore.createJob(parsed);
      response.setResponseCode(OK)
          .setMessage(String.format("%d new tasks pending for job %s",
              parsed.getJobConfig().getShardCount(), JobKeys.toPath(job)));
    } catch (ConfigurationManager.TaskDescriptionException e) {
      response.setResponseCode(INVALID_REQUEST)
          .setMessage("Invalid task description: " + e.getMessage());
    } catch (ScheduleException e) {
      response.setResponseCode(INVALID_REQUEST)
          .setMessage("Failed to schedule job - " + e.getMessage());
    }

    return response;
  }

  @Override
  public PopulateJobResponse populateJobConfig(JobConfiguration description) {
    checkNotNull(description);

    // TODO(ksweeney): check valid JobKey in description after deprecating non-environment version.

    PopulateJobResponse response = new PopulateJobResponse();
    try {
      response.setPopulated(ParsedConfiguration.fromUnparsed(description).getTaskConfigs())
          .setResponseCode(OK)
          .setMessage("Tasks populated");
    } catch (TaskDescriptionException e) {
      response.setResponseCode(INVALID_REQUEST)
          .setMessage("Invalid configuration: " + e.getMessage());
    }
    return response;
  }

  @Override
  public StartCronResponse startCronJob(JobKey jobKey, SessionKey session) {
    checkNotNull(session);
    JobKeys.assertValid(jobKey);

    StartCronResponse response = new StartCronResponse();
    try {
      sessionValidator.checkAuthenticated(session, jobKey.getRole());
    } catch (AuthFailedException e) {
      response.setResponseCode(AUTH_FAILED).setMessage(e.getMessage());
      return response;
    }

    try {
      schedulerCore.startCronJob(jobKey);
      response.setResponseCode(OK).setMessage("Cron run started.");
    } catch (ScheduleException e) {
      response.setResponseCode(INVALID_REQUEST)
          .setMessage("Failed to start cron job - " + e.getMessage());
    } catch (TaskDescriptionException e) {
      response.setResponseCode(ERROR).setMessage("Invalid task description: " + e.getMessage());
    }

    return response;
  }

  // TODO(William Farner): Provide status information about cron jobs here.
  @Override
  public ScheduleStatusResponse getTasksStatus(TaskQuery query) {
    checkNotNull(query);

    Set<ScheduledTask> tasks = Storage.Util.weaklyConsistentFetchTasks(storage, query);

    ScheduleStatusResponse response = new ScheduleStatusResponse();
    if (tasks.isEmpty()) {
      response.setResponseCode(INVALID_REQUEST)
          .setMessage("No tasks found for query: " + query);
    } else {
      response.setResponseCode(OK)
          .setTasks(ImmutableList.copyOf(tasks));
    }

    return response;
  }

  private boolean isAdmin(SessionKey session) {
    try {
      sessionValidator.checkAuthorized(session, Capability.ROOT);
      return true;
    } catch (AuthFailedException e) {
      return false;
    }
  }

  @Override
  public GetJobsResponse getJobs(@Nullable String maybeNullRole) {
    Optional<String> ownerRole = Optional.fromNullable(maybeNullRole);


    // Ensure we only return one JobConfiguration for each JobKey.
    Map<JobKey, JobConfiguration> jobs = Maps.newHashMap();

    // Query the task store, find immediate jobs, and synthesize a JobConfiguration for them.
    // This is necessary because the ImmediateJobManager doesn't store jobs directly and
    // ImmediateJobManager#getJobs always returns an empty Collection.
    Query.Builder scope = ownerRole.isPresent()
        ? Query.roleScoped(ownerRole.get())
        : Query.unscoped();
    Multimap<JobKey, ScheduledTask> tasks =  Multimaps.index(
        Storage.Util.weaklyConsistentFetchTasks(storage, scope.active()),
        Tasks.SCHEDULED_TO_JOB_KEY);

    jobs.putAll(Maps.transformEntries(tasks.asMap(),
        new Maps.EntryTransformer<JobKey, Collection<ScheduledTask>, JobConfiguration>() {
          @Override
          public JobConfiguration transformEntry(JobKey jobKey, Collection<ScheduledTask> tasks) {

            // Pick an arbitrary task for each immediate job. The chosen task might not be the most
            // recent if the job is in the middle of an update or some shards have been selectively
            // created.
            ScheduledTask firstTask = tasks.iterator().next();
            firstTask.getAssignedTask().getTask().unsetShardId();
            return new JobConfiguration()
                .setKey(jobKey)
                .setOwner(firstTask.getAssignedTask().getTask().getOwner())
                .setTaskConfig(firstTask.getAssignedTask().getTask())
                .setShardCount(tasks.size());
          }
        }));

    // Get cron jobs directly from the manager. Do this after querying the task store so the real
    // template JobConfiguration for a cron job will overwrite the synthesized one that could have
    // been created above.
    Predicate<JobConfiguration> configFilter = ownerRole.isPresent()
        ? Predicates.compose(Predicates.equalTo(ownerRole.get()), JobKeys.CONFIG_TO_ROLE)
        : Predicates.<JobConfiguration>alwaysTrue();
    jobs.putAll(Maps.uniqueIndex(
        FluentIterable.from(cronJobManager.getJobs()).filter(configFilter),
        JobKeys.FROM_CONFIG));

    return new GetJobsResponse()
        .setConfigs(ImmutableSet.copyOf(jobs.values()))
        .setResponseCode(OK);
  }

  private void validateSessionKeyForTasks(SessionKey session, TaskQuery taskQuery)
      throws AuthFailedException {

    Set<ScheduledTask> tasks = Storage.Util.consistentFetchTasks(storage, taskQuery);
    for (String role : ImmutableSet.copyOf(Iterables.transform(tasks, GET_ROLE))) {
      sessionValidator.checkAuthenticated(session, role);
    }
  }

  @Override
  public KillResponse killTasks(final TaskQuery query, SessionKey session) {
    // TODO(wfarner): Determine whether this is a useful function, or if it should simply be
    //     switched to 'killJob'.

    checkNotNull(query);
    checkNotNull(session);
    checkNotNull(session.getUser());

    KillResponse response = new KillResponse();

    if (query.getJobName() != null && StringUtils.isBlank(query.getJobName())) {
      response.setResponseCode(INVALID_REQUEST).setMessage(
          String.format("Invalid job name: '%s'", query.getJobName()));
      return response;
    }

    if (isAdmin(session)) {
      LOG.info("Granting kill query to admin user: " + query);
    } else {
      try {
        validateSessionKeyForTasks(session, query);
      } catch (AuthFailedException e) {
        response.setResponseCode(AUTH_FAILED).setMessage(e.getMessage());
        return response;
      }
    }

    try {
      schedulerCore.killTasks(query, session.getUser());
    } catch (ScheduleException e) {
      response.setResponseCode(INVALID_REQUEST).setMessage(e.getMessage());
      return response;
    }

    BackoffHelper backoff = new BackoffHelper(killTaskInitialBackoff, killTaskMaxBackoff, true);
    final TaskQuery activeQuery = query.setStatuses(Tasks.ACTIVE_STATES);
    try {
      backoff.doUntilSuccess(new Supplier<Boolean>() {
        @Override public Boolean get() {
          Set<ScheduledTask> tasks = Storage.Util.consistentFetchTasks(storage, activeQuery);
          if (tasks.isEmpty()) {
            LOG.info("Tasks all killed, done waiting.");
            return true;
          } else {
            LOG.info("Jobs not yet killed, waiting...");
            return false;
          }
        }
      });
      response.setResponseCode(OK).setMessage("Tasks killed.");
    } catch (InterruptedException e) {
      LOG.warning("Interrupted while trying to kill tasks: " + e);
      Thread.currentThread().interrupt();
      response.setResponseCode(ERROR).setMessage("killTasks thread was interrupted.");
    } catch (BackoffHelper.BackoffStoppedException e) {
      response.setResponseCode(ERROR).setMessage("Tasks were not killed in time.");
    }
    return response;
  }

  @Override
  public StartUpdateResponse startUpdate(JobConfiguration job, SessionKey session) {
    checkNotNull(job);
    checkNotNull(session);

    StartUpdateResponse response = new StartUpdateResponse();
    if (!JobKeys.isValid(job.getKey())) {
      return response.setResponseCode(INVALID_REQUEST)
          .setMessage("Invalid job key: " + job.getKey());
    }

    try {
      sessionValidator.checkAuthenticated(session, job.getOwner().getRole());
    } catch (AuthFailedException e) {
      return response.setResponseCode(AUTH_FAILED).setMessage(e.getMessage());
    }

    try {
      Optional<String> token =
          schedulerCore.initiateJobUpdate(ParsedConfiguration.fromUnparsed(job));
      response.setResponseCode(OK);
      response.setRollingUpdateRequired(token.isPresent());
      if (token.isPresent()) {
        response.setUpdateToken(token.get());
        response.setMessage("Update successfully started.");
      } else {
        response.setMessage("Job successfully updated.");
      }
    } catch (ScheduleException e) {
      response.setResponseCode(INVALID_REQUEST).setMessage(e.getMessage());
    } catch (ConfigurationManager.TaskDescriptionException e) {
      response.setResponseCode(INVALID_REQUEST).setMessage(e.getMessage());
    }

    return response;
  }

  @Override
  public UpdateShardsResponse updateShards(
      JobKey jobKey,
      Set<Integer> shards,
      String updateToken,
      SessionKey session) {

    JobKeys.assertValid(jobKey);
    checkNotBlank(shards);
    checkNotBlank(updateToken);
    checkNotNull(session);

    // TODO(ksweeney): Validate session key here

    UpdateShardsResponse response = new UpdateShardsResponse();
    try {
      response
          .setShards(schedulerCore.updateShards(jobKey, session.getUser(), shards, updateToken))
          .setResponseCode(UpdateResponseCode.OK)
          .setMessage("Successfully started update of shards: " + shards);
    } catch (ScheduleException e) {
      response.setResponseCode(UpdateResponseCode.INVALID_REQUEST).setMessage(e.getMessage());
    }

    return response;
  }

  @Override
  public RollbackShardsResponse rollbackShards(
      JobKey jobKey,
      Set<Integer> shards,
      String updateToken,
      SessionKey session) {

    JobKeys.assertValid(jobKey);
    checkNotBlank(shards);
    checkNotBlank(updateToken);
    checkNotNull(session);

    // TODO(ksweeney): Validate session key here

    RollbackShardsResponse response = new RollbackShardsResponse();
    try {
      response
          .setShards(schedulerCore.rollbackShards(jobKey, session.getUser(), shards, updateToken))
          .setResponseCode(UpdateResponseCode.OK)
          .setMessage("Successfully started rollback of shards: " + shards);
    } catch (ScheduleException e) {
      response.setResponseCode(UpdateResponseCode.INVALID_REQUEST).setMessage(e.getMessage());
    }

    return response;
  }

  @Override
  public FinishUpdateResponse finishUpdate(
      JobKey jobKey,
      UpdateResult updateResult,
      String updateToken,
      SessionKey session) {

    JobKeys.assertValid(jobKey);
    checkNotNull(session);

    // TODO(ksweeney): Validate session key here

    FinishUpdateResponse response = new FinishUpdateResponse();
    Optional<String> token = updateResult == UpdateResult.TERMINATE
        ? Optional.<String>absent() : Optional.of(updateToken);
    try {
      schedulerCore.finishUpdate(jobKey, session.getUser(), token, updateResult);
      response.setResponseCode(OK).setMessage("Update successfully finished.");
    } catch (ScheduleException e) {
      response.setResponseCode(ResponseCode.INVALID_REQUEST).setMessage(e.getMessage());
    }

    return response;
  }

  @Override
  public RestartShardsResponse restartShards(
      JobKey jobKey,
      Set<Integer> shardIds,
      SessionKey session) {

    JobKeys.assertValid(jobKey);
    MorePreconditions.checkNotBlank(shardIds);
    checkNotNull(session);

    RestartShardsResponse response = new RestartShardsResponse();
    try {
      sessionValidator.checkAuthenticated(session, jobKey.getRole());
    } catch (AuthFailedException e) {
      response.setResponseCode(AUTH_FAILED).setMessage(e.getMessage());
      return response;
    }

    try {
      schedulerCore.restartShards(jobKey, shardIds, session.getUser());
      response.setResponseCode(OK).setMessage("Shards are restarting.");
    } catch (ScheduleException e) {
      response.setResponseCode(ResponseCode.INVALID_REQUEST).setMessage(e.getMessage());
    }

    return response;
  }

  @Override
  public GetQuotaResponse getQuota(String ownerRole) {
    checkNotBlank(ownerRole);
    return new GetQuotaResponse().setQuota(quotaManager.getQuota(ownerRole));
  }

  @Override
  public StartMaintenanceResponse startMaintenance(Hosts hosts, SessionKey session) {
    return new StartMaintenanceResponse()
        .setStatuses(maintenance.startMaintenance(hosts.getHostNames()))
        .setResponseCode(OK);
  }

  @Override
  public DrainHostsResponse drainHosts(Hosts hosts, SessionKey session) {
    return new DrainHostsResponse()
        .setStatuses(maintenance.drain(hosts.getHostNames()))
        .setResponseCode(OK);
  }

  @Override
  public MaintenanceStatusResponse maintenanceStatus(Hosts hosts, SessionKey session) {
    return new MaintenanceStatusResponse()
        .setStatuses(maintenance.getStatus(hosts.getHostNames()))
        .setResponseCode(OK);
  }

  @Override
  public EndMaintenanceResponse endMaintenance(Hosts hosts, SessionKey session) {
    return new EndMaintenanceResponse()
        .setStatuses(maintenance.endMaintenance(hosts.getHostNames()))
        .setResponseCode(OK);
  }

  @Requires(whitelist = Capability.PROVISIONER)
  @Override
  public SetQuotaResponse setQuota(String ownerRole, Quota quota, SessionKey session) {
    checkNotBlank(ownerRole);
    checkNotNull(quota);
    checkNotNull(session);

    quotaManager.setQuota(ownerRole, quota);
    return new SetQuotaResponse().setResponseCode(OK).setMessage("Quota applied.");
  }

  @Override
  public ForceTaskStateResponse forceTaskState(
      String taskId,
      ScheduleStatus status,
      SessionKey session) {

    checkNotBlank(taskId);
    checkNotNull(status);
    checkNotNull(session);

    schedulerCore.setTaskStatus(
        Query.byId(taskId), status, transitionMessage(session.getUser()));
    return new ForceTaskStateResponse().setResponseCode(OK).setMessage("Transition attempted.");
  }

  @Override
  public PerformBackupResponse performBackup(SessionKey session) {
    backup.backupNow();
    return new PerformBackupResponse().setResponseCode(OK);
  }

  @Override
  public ListBackupsResponse listBackups(SessionKey session) {
    return new ListBackupsResponse()
        .setBackups(recovery.listBackups())
        .setResponseCode(OK);
  }

  @Override
  public StageRecoveryResponse stageRecovery(String backupId, SessionKey session) {
    StageRecoveryResponse response = new StageRecoveryResponse().setResponseCode(OK);
    try {
      recovery.stage(backupId);
    } catch (RecoveryException e) {
      response.setResponseCode(ERROR).setMessage(e.getMessage());
      LOG.log(Level.WARNING, "Failed to stage recovery: " + e, e);
    }

    return response;
  }

  @Override
  public QueryRecoveryResponse queryRecovery(TaskQuery query, SessionKey session) {
    QueryRecoveryResponse response = new QueryRecoveryResponse().setResponseCode(OK);
    try {
      response.setTasks(recovery.query(query));
    } catch (RecoveryException e) {
      response.setResponseCode(ERROR).setMessage(e.getMessage());
      LOG.log(Level.WARNING, "Failed to query recovery: " + e, e);
    }

    return response;
  }

  @Override
  public DeleteRecoveryTasksResponse deleteRecoveryTasks(TaskQuery query, SessionKey session) {
    DeleteRecoveryTasksResponse response = new DeleteRecoveryTasksResponse().setResponseCode(OK);
    try {
      recovery.deleteTasks(query);
    } catch (RecoveryException e) {
      response.setResponseCode(ERROR).setMessage(e.getMessage());
      LOG.log(Level.WARNING, "Failed to delete recovery tasks: " + e, e);
    }

    return response;
  }

  @Override
  public CommitRecoveryResponse commitRecovery(SessionKey session) {
    CommitRecoveryResponse response = new CommitRecoveryResponse().setResponseCode(OK);
    try {
      recovery.commit();
    } catch (RecoveryException e) {
      response.setResponseCode(ERROR).setMessage(e.getMessage());
    }

    return response;
  }

  @Override
  public GetJobUpdatesResponse getJobUpdates(SessionKey session) {
    return storage.weaklyConsistentRead(new Work.Quiet<GetJobUpdatesResponse>() {
      @Override public GetJobUpdatesResponse apply(StoreProvider storeProvider) {
        GetJobUpdatesResponse response = new GetJobUpdatesResponse().setResponseCode(OK);
        response.setJobUpdates(Sets.<JobUpdateConfiguration>newHashSet());
        UpdateStore store = storeProvider.getUpdateStore();
        for (String role : store.fetchUpdatingRoles()) {
          for (JobUpdateConfiguration config : store.fetchUpdateConfigs(role)) {
            response.addToJobUpdates(config);
          }
        }
        return response;
      }
    });
  }

  @Override
  public UnloadRecoveryResponse unloadRecovery(SessionKey session) {
    recovery.unload();
    return new UnloadRecoveryResponse().setResponseCode(OK);
  }

  @Override
  public SnapshotResponse snapshot(SessionKey session) {
    SnapshotResponse response = new SnapshotResponse();
    try {
      storage.snapshot();
      return response.setResponseCode(OK).setMessage("Compaction successful.");
    } catch (Storage.StorageException e) {
      LOG.log(Level.WARNING, "Requested snapshot failed.", e);
      return response.setResponseCode(ERROR).setMessage(e.getMessage());
    }
  }

  private static Multimap<String, JobConfiguration> jobsByKey(JobStore jobStore, JobKey jobKey) {
    ImmutableMultimap.Builder<String, JobConfiguration> matches = ImmutableMultimap.builder();
    for (String managerId : jobStore.fetchManagerIds()) {
      for (JobConfiguration job : jobStore.fetchJobs(managerId)) {
        if (job.getKey().equals(jobKey)) {
          matches.put(managerId, job);
        }
      }
    }
    return matches.build();
  }

  @Override
  public RewriteConfigsResponse rewriteConfigs(
      final RewriteConfigsRequest request,
      SessionKey session) {

    if (request.getRewriteCommandsSize() == 0) {
      return new RewriteConfigsResponse(ResponseCode.ERROR, "No rewrite commands provided.");
    }

    return storage.write(new MutateWork.Quiet<RewriteConfigsResponse>() {
      @Override public RewriteConfigsResponse apply(MutableStoreProvider storeProvider) {
        List<String> errors = Lists.newArrayList();

        for (ConfigRewrite command : request.getRewriteCommands()) {
          switch (command.getSetField()) {
            case JOB_REWRITE:
              JobConfigRewrite jobRewrite = command.getJobRewrite();
              JobConfiguration existingJob = jobRewrite.getOldJob();
              JobConfiguration rewrittenJob;
              try {
                rewrittenJob =
                    ConfigurationManager.validateAndPopulate(jobRewrite.getRewrittenJob());
              } catch (TaskDescriptionException e) {
                // We could add an error here, but this is probably a hint of something wrong in
                // the client that's causing a bad configuration to be applied.
                throw Throwables.propagate(e);
              }
              if (!existingJob.getKey().equals(rewrittenJob.getKey())) {
                errors.add("Disallowing rewrite attempting to change job key.");
              } else if (!existingJob.getName().equals(rewrittenJob.getName())) {
                errors.add("Disallowing rewrite attempting to change job name.");
              } else if (!existingJob.getOwner().equals(rewrittenJob.getOwner())) {
                errors.add("Disallowing rewrite attempting to change job owner.");
              } else {
                JobStore.Mutable jobStore = storeProvider.getJobStore();
                Multimap<String, JobConfiguration> matches =
                    jobsByKey(jobStore, existingJob.getKey());
                switch (matches.size()) {
                  case 0:
                    errors.add("No jobs found for key " + JobKeys.toPath(existingJob));
                    break;

                  case 1:
                    Map.Entry<String, JobConfiguration> match =
                        Iterables.getOnlyElement(matches.entries());
                    JobConfiguration storedJob = match.getValue();
                    if (!storedJob.equals(existingJob)) {
                      errors.add("CAS compare failed for " + JobKeys.toPath(storedJob));
                    } else {
                      jobStore.saveAcceptedJob(match.getKey(), rewrittenJob);
                    }
                    break;

                  default:
                    errors.add("Multiple jobs found for key " + JobKeys.toPath(existingJob));
                }
              }
              break;

            case SHARD_REWRITE:
              ShardConfigRewrite shardRewrite = command.getShardRewrite();
              ShardKey shardKey = shardRewrite.getShardKey();
              Iterable<ScheduledTask> tasks = storeProvider.getTaskStore().fetchTasks(
                  Query.shardScoped(shardKey.getJobKey(), shardKey.getShardId()).active());
              Optional<AssignedTask> task =
                  Optional.fromNullable(Iterables.getOnlyElement(tasks, null))
                      .transform(Tasks.SCHEDULED_TO_ASSIGNED);
              if (!task.isPresent()) {
                errors.add("No active task found for " + shardKey);
              } else if (!task.get().getTask().equals(shardRewrite.getOldTask())) {
                errors.add("CAS compare failed for " + shardKey);
              } else {
                TaskConfig newConfiguration =
                    ConfigurationManager.applyDefaultsIfUnset(shardRewrite.getRewrittenTask());
                boolean changed = storeProvider.getUnsafeTaskStore().unsafeModifyInPlace(
                    task.get().getTaskId(), newConfiguration);
                if (!changed) {
                  errors.add("Did not change " + task.get().getTaskId());
                }
              }
              break;

            default:
              throw new IllegalArgumentException("Unhandled command type " + command.getSetField());
          }
        }

        RewriteConfigsResponse resp = new RewriteConfigsResponse();
        if (!errors.isEmpty()) {
          resp.setResponseCode(ResponseCode.WARNING).setMessage(Joiner.on(", ").join(errors));
        } else {
          resp.setResponseCode(OK).setMessage("All rewrites completed successfully.");
        }
        return resp;
      }
    });
  }

  @VisibleForTesting
  static Optional<String> transitionMessage(String user) {
    return Optional.of("Transition forced by " + user);
  }
}
