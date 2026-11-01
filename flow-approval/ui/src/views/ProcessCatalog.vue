<template>
  <div class="page-card">
    <div class="page-title">流程目录</div>
    <a-spin :spinning="loading">
      <a-empty v-if="!loading && !categories.length" description="暂无已发布的流程" />
      <div v-for="category in categories" :key="category.name" style="margin-bottom: 24px">
        <a-divider orientation="left">{{ category.name }}</a-divider>
        <a-row :gutter="16">
          <a-col v-for="model in category.models" :key="model.id" :xs="24" :sm="12" :md="8" :lg="6">
            <a-card size="small" :hoverable="true" style="margin-bottom: 16px">
              <template slot="title">
                <a-icon type="partition" style="color: #1677ff; margin-right: 6px" />
                {{ model.name }}
              </template>
              <p class="muted" style="min-height: 40px">
                {{ model.description || '暂无描述' }}
              </p>
              <p class="muted" style="font-size: 12px">
                Key：{{ model.key }}　版本：{{ model.version }}
              </p>
              <a-button
                type="primary"
                size="small"
                icon="play-circle"
                :disabled="!canStart(model)"
                @click="openStart(model)"
              >
                发起流程
              </a-button>
              <span v-if="!canStart(model)" class="muted" style="font-size: 12px; margin-left: 8px">
                未发布
              </span>
            </a-card>
          </a-col>
        </a-row>
      </div>
    </a-spin>

    <a-modal
      title="发起流程"
      :visible="startVisible"
      :confirm-loading="starting"
      ok-text="发起"
      @ok="submitStart"
      @cancel="startVisible = false"
    >
      <a-form layout="vertical" v-if="currentModel">
        <a-form-item label="流程">
          <a-input :value="currentModel.name" disabled />
        </a-form-item>
        <a-form-item label="业务ID" required>
          <a-input v-model="startForm.businessId" placeholder="业务唯一标识，例如 ORDER-20260101-001" />
        </a-form-item>
        <a-form-item label="标题">
          <a-input v-model="startForm.title" placeholder="审批标题" />
        </a-form-item>
        <a-form-item label="业务详情地址">
          <a-input v-model="startForm.url" placeholder="/order/ORDER-20260101-001" />
        </a-form-item>
        <a-form-item label="流程变量（JSON）">
          <a-textarea v-model="startForm.variables" :rows="4" placeholder='{"amount": 1200, "days": 3}' />
        </a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>

<script>
import { api } from '../api';

export default {
  name: 'ProcessCatalog',
  data() {
    return {
      loading: false,
      groups: {},
      startVisible: false,
      starting: false,
      currentModel: null,
      startForm: {
        businessId: '',
        title: '',
        url: '',
        variables: '{}'
      }
    };
  },
  computed: {
    categories() {
      return Object.keys(this.groups).map(name => ({
        name,
        models: this.groups[name] || []
      }));
    }
  },
  mounted() {
    this.load();
  },
  methods: {
    load() {
      this.loading = true;
      api
        .get('/flow/model/groupList')
        .then(response => {
          const body = response.data || {};
          if (body.success === false) {
            this.$message.error(body.message || '加载流程目录失败');
            return;
          }
          this.groups = body.result || {};
        })
        .catch(() => {
          this.$message.error('加载流程目录失败');
        })
        .finally(() => {
          this.loading = false;
        });
    },
    canStart(model) {
      return (model.version && Number(model.version) >= 1) || !!model.deployTime;
    },
    openStart(model) {
      this.currentModel = model;
      const stamp = new Date().toISOString().replace(/[-:TZ.]/g, '').slice(0, 14);
      this.startForm = {
        businessId: `${model.key}-${stamp}`,
        title: `${model.name}申请`,
        url: '',
        variables: '{}'
      };
      this.startVisible = true;
    },
    submitStart() {
      if (!this.startForm.businessId) {
        this.$message.warning('请填写业务ID');
        return;
      }
      let variables = {};
      try {
        variables = this.startForm.variables ? JSON.parse(this.startForm.variables) : {};
      } catch (e) {
        this.$message.error('流程变量必须是合法 JSON');
        return;
      }
      this.starting = true;
      api
        .post('/flow/start', {
          workflowId: this.currentModel.key,
          businessId: this.startForm.businessId,
          title: this.startForm.title,
          url: this.startForm.url,
          variables
        })
        .then(response => {
          const body = response.data || {};
          if (body.success === false) {
            this.$message.error(body.message || '发起失败');
            return;
          }
          this.$message.success('流程已发起');
          this.startVisible = false;
          this.$router.push('/mine');
        })
        .catch(() => {
          this.$message.error('发起失败，请稍后重试');
        })
        .finally(() => {
          this.starting = false;
        });
    }
  }
};
</script>
