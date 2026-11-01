<template>
  <div class="page-card">
    <div class="page-title">{{ title }}</div>
    <div class="toolbar">
      <a-input
        v-model="query.title"
        placeholder="按标题搜索"
        style="width: 240px"
        allow-clear
        @press-enter="reload"
      />
      <a-button type="primary" icon="search" @click="reload">查询</a-button>
      <a-button icon="reload" @click="reset">重置</a-button>
      <span class="muted" style="margin-left: auto">共 {{ total }} 条</span>
    </div>

    <a-table
      :columns="columns"
      :data-source="records"
      :loading="loading"
      :pagination="pagination"
      :row-key="rowKey"
      size="middle"
      @change="handleTableChange"
    >
      <span slot="titleCell" slot-scope="text, record">
        <a @click="openDetail(record)">{{ text }}</a>
        <div class="muted" style="font-size: 12px">{{ record.businessKey }}</div>
      </span>
      <span slot="state" slot-scope="state">
        <a-tag :color="stateColor(state)">{{ stateText(state) }}</a-tag>
      </span>
      <span slot="action" slot-scope="text, record">
        <a @click="openDetail(record)">详情</a>
        <a-divider v-if="kind === 'todo'" type="vertical" />
        <a v-if="kind === 'todo'" @click="openApprove(record)">审批</a>
      </span>
    </a-table>

    <approval-drawer
      :visible.sync="drawerVisible"
      :record="currentRecord"
      :can-approve="drawerCanApprove"
      @done="reload"
    />
  </div>
</template>

<script>
import { api } from '../api';
import ApprovalDrawer from '../components/ApprovalDrawer.vue';

const API_MAP = {
  todo: '/flow/actBusinessData/todoList',
  done: '/flow/actBusinessData/doneList',
  mine: '/flow/actBusinessData/myApplyAList',
  all: '/flow/actBusinessData/allList'
};

export default {
  name: 'TaskList',
  components: { ApprovalDrawer },
  data() {
    return {
      loading: false,
      records: [],
      total: 0,
      pageNo: 1,
      pageSize: 10,
      query: { title: '' },
      drawerVisible: false,
      drawerCanApprove: false,
      currentRecord: null,
      columns: [
        { title: '标题', dataIndex: 'title', key: 'title', scopedSlots: { customRender: 'titleCell' }, width: '30%' },
        { title: '流程', key: 'process', customRender: (text, record) => this.processName(record), width: 140 },
        { title: '当前/办理节点', key: 'taskName', customRender: (text, record) => this.taskName(record), width: 140 },
        { title: '发起人', key: 'startUser', customRender: (text, record) => this.startUser(record), width: 110 },
        { title: '状态', dataIndex: 'state', key: 'state', scopedSlots: { customRender: 'state' }, width: 100 },
        { title: '时间', key: 'time', customRender: (text, record) => this.timeOf(record), width: 170 },
        { title: '操作', key: 'action', scopedSlots: { customRender: 'action' }, width: 120 }
      ]
    };
  },
  computed: {
    kind() {
      return (this.$route.meta && this.$route.meta.kind) || 'todo';
    },
    title() {
      return (this.$route.meta && this.$route.meta.title) || '任务列表';
    },
    pagination() {
      return {
        current: this.pageNo,
        pageSize: this.pageSize,
        total: this.total,
        showSizeChanger: true,
        showTotal: total => `共 ${total} 条`
      };
    }
  },
  watch: {
    '$route.path'() {
      this.pageNo = 1;
      this.query.title = '';
      this.reload();
    }
  },
  mounted() {
    this.reload();
  },
  methods: {
    reload() {
      this.loading = true;
      const params = {
        pageNo: this.pageNo,
        pageSize: this.pageSize
      };
      if (this.query.title) {
        params.title = this.query.title;
      }
      api
        .get(API_MAP[this.kind] || API_MAP.todo, { params })
        .then(response => {
          const body = response.data || {};
          const page = body.result || {};
          if (body.success === false) {
            this.$message.error(body.message || '查询失败');
            this.records = [];
            this.total = 0;
            return;
          }
          this.records = page.records || [];
          this.total = page.total || 0;
        })
        .catch(() => {
          this.$message.error('查询失败，请稍后重试');
        })
        .finally(() => {
          this.loading = false;
        });
    },
    reset() {
      this.query.title = '';
      this.pageNo = 1;
      this.reload();
    },
    handleTableChange(pagination) {
      this.pageNo = pagination.current;
      this.pageSize = pagination.pageSize;
      this.reload();
    },
    rowKey(record) {
      return `${record.businessKey || record.id}-${record.taskId || record.id}`;
    },
    processName(record) {
      return (
        record.processName ||
        record.name ||
        (record.businessData && (record.businessData.defName || record.businessData.defKey)) ||
        '-'
      );
    },
    taskName(record) {
      return record.taskName || record.name || (record.businessData && record.businessData.taskName) || '-';
    },
    startUser(record) {
      return record.startUserName || record.startUser || '-';
    },
    timeOf(record) {
      return record.createTime || record.startTime || record.processStartTime || '-';
    },
    stateText(state) {
      const map = { '1': '运行中', '2': '已完成', '3': '已终止', '4': '已撤销' };
      return map[String(state)] || '未知';
    },
    stateColor(state) {
      const map = { '1': 'processing', '2': 'success', '3': 'error', '4': 'default' };
      return map[String(state)] || 'default';
    },
    openDetail(record) {
      this.currentRecord = record;
      this.drawerCanApprove = false;
      this.drawerVisible = true;
    },
    openApprove(record) {
      this.currentRecord = record;
      this.drawerCanApprove = true;
      this.drawerVisible = true;
    }
  }
};
</script>
