package com.payneteasy.herdrwatch;

import com.payneteasy.herdrwatch.http.ServersResource;
import com.payneteasy.herdrwatch.model.DataSource;
import com.payneteasy.herdrwatch.model.HostDef;
import com.payneteasy.herdrwatch.model.Model;
import com.payneteasy.herdrwatch.snapshot.SnapshotAgent;
import com.payneteasy.herdrwatch.snapshot.SnapshotAgentCompact;
import com.payneteasy.herdrwatch.snapshot.SnapshotAgentStatus;
import com.payneteasy.herdrwatch.snapshot.SnapshotError;
import com.payneteasy.herdrwatch.snapshot.SnapshotResponseCompact;
import com.payneteasy.herdrwatch.snapshot.SnapshotResponseFull;
import com.payneteasy.herdrwatch.snapshot.SnapshotResponseStatus;
import com.payneteasy.herdrwatch.snapshot.SnapshotTime;
import com.payneteasy.herdrwatch.snapshot.SnapshotUsage;
import com.payneteasy.herdrwatch.usage.ClaudeUsage;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Регистрация типов для рефлексии в native-образе. Наши record'ы сериализуются
 * Jackson'ом, причём часть — «ручным» ObjectMapper (HostStore → state-файл) и через
 * StreamEvent.data типа Object (SSE), поэтому автоматической регистрации от
 * quarkus-rest недостаточно. В JVM-режиме аннотация ни на что не влияет.
 */
@RegisterForReflection(
        targets = {
            Model.HostState.class,
            Model.WorkspaceInfo.class,
            Model.AgentInfo.class,
            Model.WorktreeInfo.class,
            Model.StreamEvent.class,
            Model.Health.class,
            DataSource.class,
            HostDef.class,
            HostStore.StateFile.class,
            ServersResource.HostRequest.class,
            ServersResource.ServerView.class,
            SnapshotAgent.class,
            SnapshotAgentCompact.class,
            SnapshotAgentStatus.class,
            SnapshotResponseFull.class,
            SnapshotResponseCompact.class,
            SnapshotResponseStatus.class,
            SnapshotTime.class,
            SnapshotError.class,
            SnapshotUsage.class,
            SnapshotUsage.Window.class,
            ClaudeUsage.class,
            ClaudeUsage.Window.class,
            ClaudeUsage.Windows.class,
            ClaudeUsage.State.class,
        })
public class NativeReflectionConfig {
}
