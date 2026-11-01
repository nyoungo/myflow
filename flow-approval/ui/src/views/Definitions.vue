<template>
  <div class="page-card">
    <div class="page-title">流程定义（已发布）</div>
    <div class="toolbar">
      <a-input
        v-model="query.name"
        placeholder="按名称搜索"
        style="width: 220px"
        allow-clear
        @press-enter="reload"
      />
      <a-button type="primary" icon="search" @click="reload">查询</a-button>
      <a-button icon="reload" @click="reset">重置</a-button>
      <a-upload
        accept=".xml,.bpmn"
        :show-upload-list="false"
        :custom-request="upload"
        style="margin-left: auto"
      >
        <a-button icon="upload">部署 BPMN 文件</a-button>
      </a-upload>
    </div>

    <a-table
      :columns="columns"
      :data-source="records"
      :loading="loading"
      :pagination="pagination"
      row-key="id"
      size="middle"
      @change="handleTableChange"
    >
      <span slot="status" slot-scope="text">
        <a-tag :color="text === 'SUSPENDED' ? 'orange' : 'green'">
          {{ text === 'SUSPENDED' ? '已挂起' : '已发布' }}
        </a-tag>
      </span>
      <span slot="action" slot-scope="text, record">
        <a @click="detail(record)">XML</a>
        <a-divider type="vertical" />
        <a @click="toggle(record)">{{ record.status === 'SUSPENDED' ? '激活' : '挂起' }}</a>
        <a-divider type="vertical" />
        <a @click="convert(record)">转模型</a>
        <a-divider type="vertical" />
        <a-popconfirm title="确定删除该部署？" @confirm="remove(record)">
          <a style="color: #ff4d4f">删除</a>
        </a-popconfirm>
      </span>
    </a-table>

    <a-modal
      title="流程定义 XML"
      :visible="xmlVisible"
      :footer="null"
      width="900px"
      @cancel="xmlVisible = false"
    >
      <pre style="max-height: 520px; overflow: auto; background: #fafafa; padding: 12px; border-radius: 4px">{{
        xmlText
      }}</pre>
    </a-modal>
  </div>
</template>

<script>
import { api } from '../api';

export default {
  name: 'Definitions',
  data() {
    return {
      loading: false,
      records: [],
      total: 0,
      pageNo: 1,
      pageSize: 10,
      query: { name: '' },
      xmlVisible: false,
      xmlText: '',
      columns: [
        { title: '流程名称', dataIndex: 'name', key: 'name' },
        { title: 'Key', dataIndex: 'key', key: 'key', width: 150 },
        { title: '分类', dataIndex: 'category', key: 'category', width: 100 },
        { title: '版本', dataIndex: 'version', key: 'version', width: 80 },
        { title: '状态', dataIndex: 'status', key: 'status', scopedSlots: { customRender: 'status' }, width: 100 },
        { title: '部署时间', dataIndex: 'deployTime', key: 'deployTime', width: 170 },
        { title: '操作', key: 'action', scopedSlots: { customRender: 'action' }, width: 240 }
      ]
    };
  },
  computed: {
    pagination() {
      return { current: this.pageNo, pageSize: this.pageSize, total: this.total, showSizeChanger: true };
    }
  },
  mounted() {
    this.reload();
  },
  methods: {
    reload() {
      this.loading = true;
      const params = { pageNo: this.pageNo, pageSize: this.pageSize };
      if (this.query.name) {
        params.name = this.query.name;
      }
      api
        .get('/act/procdef/list', { params })
        .then(response => {
          const body = response.data || {};
          const page = body.result || {};
          if (body.success === false) {
            this.$message.error(body.message || '查询失败');
            return;
          }
          this.records = page.records || [];
          this.total = page.total || 0;
        })
        .catch(() => this.$message.error('查询失败'))
        .finally(() => {
          this.loading = false;
        });
    },
    reset() {
      this.query.name = '';
      this.pageNo = 1;
      this.reload();
    },
    handleTableChange(pagination) {
      this.pageNo = pagination.current;
      this.pageSize = pagination.pageSize;
      this.reload();
    },
    detail(record) {
      api
        .get('/act/procdef/read', {
          params: { id: record.id, type: 'xml' },
          responseType: 'text',
          transformResponse: [data => data]
        })
        .then(response => {
          this.xmlText = response.data || '';
          this.xmlVisible = true;
        })
        .catch(() => this.$message.error('读取 XML 失败'));
    },
    toggle(record) {
      const state = record.status === 'SUSPENDED' ? 1 : 2;
      api
        .post('/act/procdef/status', { id: record.id, state })
        .then(response => {
          const body = response.data || {};
          if (body.success === false) {
            this.$message.error(body.message || '操作失败');
            return;
          }
          this.$message.success('操作成功');
          this.reload();
        })
        .catch(() => this.$message.error('操作失败'));
    },
    convert(record) {
      api
        .get('/act/procdef/convertToModel', { params: { id: record.id } })
        .then(response => {
          const body = response.data || {};
          if (body.success === false) {
            this.$message.error(body.message || '转换失败');
            return;
          }
          this.$message.success('已转为模型，可在流程模板中编辑');
          this.$router.push('/templates');
        })
        .catch(() => this.$message.error('转换失败'));
    },
    remove(record) {
      api
        .post('/act/procdef/delete', [record.deploymentId || record.id])
        .then(response => {
          const body = response.data || {};
          if (body.success === false) {
            this.$message.error(body.message || '删除失败');
            return;
          }
          this.$message.success('删除成功');
          this.reload();
        })
        .catch(() => this.$message.error('删除失败'));
    },
    upload(options) {
      const formData = new FormData();
      formData.append('file', options.file);
      api
        .post('/act/procdef/deploy', formData, {
          headers: { 'Content-Type': 'multipart/form-data' }
        })
        .then(response => {
          const body = response.data || {};
          if (body.success === false) {
            this.$message.error(body.message || '部署失败');
            options.onError(new Error(body.message || '部署失败'));
            return;
          }
          this.$message.success('部署成功');
          options.onSuccess(body.result || {});
          this.reload();
        })
        .catch(error => {
          this.$message.error('部署失败');
          options.onError(error);
        });
    }
  }
};
</script>
