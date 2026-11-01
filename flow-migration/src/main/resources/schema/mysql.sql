CREATE TABLE IF NOT EXISTS flow_def (
    id             VARCHAR(64)  NOT NULL,
    rev            INT          DEFAULT 1,
    def_name       VARCHAR(200) NOT NULL,
    def_key        VARCHAR(100) NOT NULL,
    category       VARCHAR(100) DEFAULT NULL,
    description    VARCHAR(500) DEFAULT NULL,
    create_time    DATETIME     DEFAULT NULL,
    update_time    DATETIME     DEFAULT NULL,
    version        INT          NOT NULL DEFAULT 0,
    meta_info      VARCHAR(2000) DEFAULT NULL,
    deployment_id  VARCHAR(100) DEFAULT NULL,
    tenant_id      VARCHAR(64)  DEFAULT NULL,
    status         VARCHAR(20)  DEFAULT 'DRAFT',
    bpmn_xml       LONGTEXT,
    svg_xml        LONGTEXT,
    deploy_time    DATETIME     DEFAULT NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_flow_def_key ON flow_def (def_key, version);

CREATE INDEX idx_flow_def_deployment ON flow_def (deployment_id);

CREATE TABLE IF NOT EXISTS flow_inst (
    id              VARCHAR(64)  NOT NULL,
    proc_inst_id    VARCHAR(64)  DEFAULT NULL,
    business_id     VARCHAR(100) DEFAULT NULL,
    business_key    VARCHAR(200) DEFAULT NULL,
    task_id         VARCHAR(500) DEFAULT NULL,
    task_name       VARCHAR(500) DEFAULT NULL,
    start_user_id   VARCHAR(64)  DEFAULT NULL,
    start_user_name VARCHAR(100) DEFAULT NULL,
    start_depart    VARCHAR(64)  DEFAULT NULL,
    start_role      VARCHAR(500) DEFAULT NULL,
    start_time      DATETIME     DEFAULT NULL,
    end_time        DATETIME     DEFAULT NULL,
    title           VARCHAR(500) DEFAULT NULL,
    state           VARCHAR(10)  DEFAULT '1',
    tenant_id       VARCHAR(64)  DEFAULT NULL,
    ext_id          VARCHAR(100) DEFAULT NULL,
    def_id          VARCHAR(64)  DEFAULT NULL,
    def_key         VARCHAR(100) DEFAULT NULL,
    def_name        VARCHAR(200) DEFAULT NULL,
    url             VARCHAR(500) DEFAULT NULL,
    variables       LONGTEXT,
    active_tokens   LONGTEXT,
    activities      LONGTEXT,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_flow_inst_business_key ON flow_inst (business_key);

CREATE INDEX idx_flow_inst_start_user ON flow_inst (start_user_id, state);

CREATE INDEX idx_flow_inst_def ON flow_inst (def_key, def_id);

CREATE TABLE IF NOT EXISTS flow_task (
    id                VARCHAR(64)  NOT NULL,
    inst_id           VARCHAR(64)  NOT NULL,
    def_id            VARCHAR(64)  DEFAULT NULL,
    task_def_key      VARCHAR(100) DEFAULT NULL,
    name              VARCHAR(200) DEFAULT NULL,
    assignee          VARCHAR(64)  DEFAULT NULL,
    candidate_users   VARCHAR(1000) DEFAULT NULL,
    candidate_groups  VARCHAR(1000) DEFAULT NULL,
    owner             VARCHAR(64)  DEFAULT NULL,
    delegation_state  VARCHAR(20)  DEFAULT NULL,
    form_key          VARCHAR(200) DEFAULT NULL,
    priority          INT          DEFAULT NULL,
    status            VARCHAR(20)  DEFAULT NULL,
    token_id          VARCHAR(64)  DEFAULT NULL,
    start_time        DATETIME     DEFAULT NULL,
    claim_time        DATETIME     DEFAULT NULL,
    end_time          DATETIME     DEFAULT NULL,
    duration          BIGINT       DEFAULT NULL,
    delete_reason     VARCHAR(500) DEFAULT NULL,
    parent_task_id    VARCHAR(64)  DEFAULT NULL,
    tenant_id         VARCHAR(64)  DEFAULT NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_flow_task_inst ON flow_task (inst_id, status);

CREATE INDEX idx_flow_task_assignee ON flow_task (assignee, status);

CREATE TABLE IF NOT EXISTS flow_comment (
    id            VARCHAR(64)   NOT NULL,
    inst_id       VARCHAR(64)   NOT NULL,
    task_id       VARCHAR(64)   DEFAULT NULL,
    task_def_key  VARCHAR(100)  DEFAULT NULL,
    task_name     VARCHAR(200)  DEFAULT NULL,
    comment_type  VARCHAR(20)   DEFAULT NULL,
    user_id       VARCHAR(64)   DEFAULT NULL,
    user_name     VARCHAR(100)  DEFAULT NULL,
    message       VARCHAR(2000) DEFAULT NULL,
    flow_state    VARCHAR(10)   DEFAULT NULL,
    create_time   DATETIME      DEFAULT NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_flow_comment_inst ON flow_comment (inst_id, create_time);
