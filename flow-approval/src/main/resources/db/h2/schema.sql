-- H2 (MODE=MySQL) schema for flow-approval
CREATE TABLE IF NOT EXISTS flow_def (
    id             VARCHAR(64)  NOT NULL,
    rev            INT          DEFAULT 1,
    def_name       VARCHAR(200) NOT NULL,
    def_key        VARCHAR(100) NOT NULL,
    category       VARCHAR(100),
    description    VARCHAR(500),
    create_time    DATETIME,
    update_time    DATETIME,
    version        INT          NOT NULL DEFAULT 0,
    meta_info      VARCHAR(2000),
    deployment_id  VARCHAR(100),
    tenant_id      VARCHAR(64),
    status         VARCHAR(20)  DEFAULT 'DRAFT',
    bpmn_xml       CLOB,
    svg_xml        CLOB,
    deploy_time    DATETIME,
    PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_flow_def_key ON flow_def (def_key, version);
CREATE INDEX IF NOT EXISTS idx_flow_def_deployment ON flow_def (deployment_id);

CREATE TABLE IF NOT EXISTS flow_inst (
    id              VARCHAR(64)  NOT NULL,
    proc_inst_id    VARCHAR(64),
    business_id     VARCHAR(100),
    business_key    VARCHAR(200),
    task_id         VARCHAR(500),
    task_name       VARCHAR(500),
    start_user_id   VARCHAR(64),
    start_user_name VARCHAR(100),
    start_depart    VARCHAR(64),
    start_role      VARCHAR(500),
    start_time      DATETIME,
    end_time        DATETIME,
    title           VARCHAR(500),
    state           VARCHAR(10)  DEFAULT '1',
    tenant_id       VARCHAR(64),
    ext_id          VARCHAR(100),
    def_id          VARCHAR(64),
    def_key         VARCHAR(100),
    def_name        VARCHAR(200),
    url             VARCHAR(500),
    variables       CLOB,
    active_tokens   CLOB,
    activities      CLOB,
    PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_flow_inst_business_key ON flow_inst (business_key);
CREATE INDEX IF NOT EXISTS idx_flow_inst_start_user ON flow_inst (start_user_id, state);
CREATE INDEX IF NOT EXISTS idx_flow_inst_def ON flow_inst (def_key, def_id);

CREATE TABLE IF NOT EXISTS flow_task (
    id                VARCHAR(64)  NOT NULL,
    inst_id           VARCHAR(64)  NOT NULL,
    def_id            VARCHAR(64),
    task_def_key      VARCHAR(100),
    name              VARCHAR(200),
    assignee          VARCHAR(64),
    candidate_users   VARCHAR(1000),
    candidate_groups  VARCHAR(1000),
    owner             VARCHAR(64),
    delegation_state  VARCHAR(20),
    form_key          VARCHAR(200),
    priority          INT,
    status            VARCHAR(20),
    token_id          VARCHAR(64),
    start_time        DATETIME,
    claim_time        DATETIME,
    end_time          DATETIME,
    duration          BIGINT,
    delete_reason     VARCHAR(500),
    parent_task_id    VARCHAR(64),
    tenant_id         VARCHAR(64),
    PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_flow_task_inst ON flow_task (inst_id, status);
CREATE INDEX IF NOT EXISTS idx_flow_task_assignee ON flow_task (assignee, status);

CREATE TABLE IF NOT EXISTS flow_comment (
    id            VARCHAR(64)   NOT NULL,
    inst_id       VARCHAR(64)   NOT NULL,
    task_id       VARCHAR(64),
    task_def_key  VARCHAR(100),
    task_name     VARCHAR(200),
    comment_type  VARCHAR(20),
    user_id       VARCHAR(64),
    user_name     VARCHAR(100),
    message       VARCHAR(2000),
    flow_state    VARCHAR(10),
    create_time   DATETIME,
    PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_flow_comment_inst ON flow_comment (inst_id, create_time);

CREATE TABLE IF NOT EXISTS sys_user (
    id          VARCHAR(64)  NOT NULL,
    username    VARCHAR(64)  NOT NULL,
    password    VARCHAR(64),
    realname    VARCHAR(100),
    avatar      VARCHAR(500),
    email       VARCHAR(100),
    phone       VARCHAR(32),
    dept_id     VARCHAR(64),
    role_codes  VARCHAR(500),
    leader_id   VARCHAR(64),
    status      INT          DEFAULT 1,
    create_time DATETIME,
    PRIMARY KEY (id),
    CONSTRAINT uk_sys_user_username UNIQUE (username)
);
