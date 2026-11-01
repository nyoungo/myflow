<template>
  <div class="page-card">
    <div class="page-title">流程模板（草稿）</div>
    <div class="toolbar">
      <a-input
        v-model="query.name"
        placeholder="按模板名称搜索"
        style="width: 220px"
        allow-clear
        @press-enter="reload"
      />
      <a-button type="primary" icon="search" @click="reload">查询</a-button>
      <a-button icon="reload" @click="reset">重置</a-button>
      <a-button type="primary" icon="plus" style="margin-left: auto" @click="openCreate">新建模板</a-button>
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
        <a-tag :color="text === 'DRAFT' ? 'blue' : text === 'SUSPENDED' ? 'orange' : 'green'">
          {{ text === 'DRAFT' ? '草稿' : text === 'SUSPENDED' ? '已挂起' : '已发布' }}
        </a-tag>
      </span>
      <span slot="action" slot-scope="text, record">
        <a @click="design(record)">设计</a>
        <a-divider type="vertical" />
        <a @click="openEdit(record)">编辑</a>
        <a-divider type="vertical" />
        <a @click="publish(record)">发布</a>
        <a-divider type="vertical" />
        <a @click="exportXml(record)">导出</a>
        <a-divider type="vertical" />
        <a-popconfirm title="确定删除该模板？" @confirm="remove(record)">
          <a style="color: #ff4d4f">删除</a>
        </a-popconfirm>
      </span>
    </a-table>

    <a-modal
      :title="form.id ? '编辑模板' : '新建模板'"
      :visible="formVisible"
      :confirm-loading="saving"
      @ok="save"
      @cancel="formVisible = false"
    >
      <a-form layout="vertical">
        <a-form-item label="模板名称" required>
          <a-input v-model="form.name" placeholder="例如 请假审批" />
        </a-form-item>
        <a-form-item label="流程 Key" :help="form.id ? '已保存的 Key 修改后会影响新发起的流程' : '唯一标识，建议英文，例如 leave'">
          <a-input v-model="form.key" placeholder="leave" />
        </a-form-item>
        <a-form-item label="分类">
          <a-input v-model="form.category" placeholder="例如 人事" />
        </a-form-item>
        <a-form-item label="描述">
          <a-textarea v-model="form.description" :rows="3" placeholder="模板说明" />
        </a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>

<script>
import { api } from '../api';

export default {
  name: 'Templates',
  data() {
    return {
      loading: false,
      saving: false,
      records: [],
      total: 0,
      pageNo: 1,
      pageSize: 10,
      query: { name: '' },
      formVisible: false,
      form: { id: '', name: '', key: '', category: '', description: '' },
      columns: [
        { title: '模板名称', dataIndex: 'name', key: 'name' },
        { title: 'Key', dataIndex: 'key', key: 'key', width: 160 },
        { title: '分类', dataIndex: 'category', key: 'category', width: 100 },
        { title: '版本', dataIndex: 'version', key: 'version', width: 80 },
        { title: '状态', dataIndex: 'status', key: 'status', scopedSlots: { customRender: 'status' }, width: 100 },
        { title: '更新时间', dataIndex: 'lastUpdateTime', key: 'lastUpdateTime', width: 170 },
        { title: '操作', key: 'action', scopedSlots: { customRender: 'action' }, width: 260 }
      ]
    };
  },
  computed: {
    pagination() {
      return {
        current: this.pageNo,
        pageSize: this.pageSize,
        total: this.total,
        showSizeChanger: true
      };
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
        .get('/act/model/list', { params })
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
    openCreate() {
      this.form = { id: '', name: '', key: '', category: '', description: '' };
      this.formVisible = true;
    },
    openEdit(record) {
      this.form = {
        id: record.id,
        name: record.name,
        key: record.key,
        category: record.category,
        description: record.description
      };
      this.formVisible = true;
    },
    save() {
      if (!this.form.name) {
        this.$message.warning('请填写模板名称');
        return;
      }
      this.saving = true;
      const request = this.form.id
        ? api.put('/act/model/edit', this.form)
        : api.post('/act/model/save', this.form);
      request
        .then(response => {
          const body = response.data || {};
          if (body.success === false) {
            this.$message.error(body.message || '保存失败');
            return;
          }
          this.$message.success('保存成功');
          this.formVisible = false;
          this.reload();
        })
        .catch(() => this.$message.error('保存失败'))
        .finally(() => {
          this.saving = false;
        });
    },
    publish(record) {
      api
        .get('/act/model/deploy', { params: { id: record.id } })
        .then(response => {
          const body = response.data || {};
          if (body.success === false) {
            this.$message.error(body.message || '发布失败');
            return;
          }
          this.$message.success('发布成功');
          this.reload();
        })
        .catch(() => this.$message.error('发布失败'));
    },
    remove(record) {
      api
        .delete('/act/model/delete', { params: { id: record.id } })
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
    exportXml(record) {
      api
        .get('/act/model/export', { params: { id: record.id, type: 'xml' }, responseType: 'blob' })
        .then(response => {
          const blob = new Blob([response.data], { type: 'application/xml' });
          const url = window.URL.createObjectURL(blob);
          const link = document.createElement('a');
          link.href = url;
          link.download = `${record.key || record.name}.bpmn20.xml`;
          document.body.appendChild(link);
          link.click();
          document.body.removeChild(link);
          window.URL.revokeObjectURL(url);
        })
        .catch(() => this.$message.error('导出失败'));
    },
    design(record) {
      this.$router.push(`/designer/${record.id}`);
    }
  }
};
</script>
