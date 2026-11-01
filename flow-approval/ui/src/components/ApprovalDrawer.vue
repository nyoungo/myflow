<template>
  <a-drawer
    title="流程详情"
    :width="760"
    :visible="visible"
    :body-style="{ paddingBottom: '60px' }"
    @close="close"
  >
    <a-spin :spinning="loading">
      <div v-if="record">
        <a-descriptions :column="2" size="small" bordered>
          <a-descriptions-item label="标题" :span="2">{{ record.title }}</a-descriptions-item>
          <a-descriptions-item label="流程">{{ processName }}</a-descriptions-item>
          <a-descriptions-item label="业务键">{{ businessKey }}</a-descriptions-item>
          <a-descriptions-item label="发起人">{{ startUser }}</a-descriptions-item>
          <a-descriptions-item label="状态">
            <a-tag :color="stateColor">{{ stateText }}</a-tag>
          </a-descriptions-item>
          <a-descriptions-item label="当前节点">{{ record.taskName || record.name || '-' }}</a-descriptions-item>
          <a-descriptions-item label="开始时间">{{ record.startTime || record.createTime || '-' }}</a-descriptions-item>
        </a-descriptions>

        <a-divider orientation="left">流程变量</a-divider>
        <pre style="background: #fafafa; padding: 8px; border-radius: 4px; max-height: 180px; overflow: auto">{{
          variablesText
        }}</pre>

        <a-divider orientation="left">流程图</a-divider>
        <div class="diagram-box">
          <img v-if="pic" class="diagram-image" :src="picSrc" alt="流程图" />
          <a-empty v-else description="暂无流程图" />
        </div>

        <a-divider orientation="left">审批历史</a-divider>
        <a-timeline v-if="history.length">
          <a-timeline-item v-for="item in history" :key="item.id" :color="item.endTime ? 'green' : 'blue'">
            <div>
              <strong>{{ item.name || item.taskDefKey }}</strong>
              <span class="muted" style="margin-left: 8px">
                {{ item.assigneeName || item.assignee || '未指定' }}
              </span>
              <span class="muted" style="margin-left: 8px">{{ item.endTime || item.startTime }}</span>
            </div>
            <div v-if="item.comment" class="history-comment">{{ item.comment }}</div>
            <div v-if="item.deleteReason" class="muted" style="font-size: 12px">
              {{ item.deleteReason }}
            </div>
          </a-timeline-item>
        </a-timeline>
        <a-empty v-else description="暂无审批记录" />

        <template v-if="canApprove">
          <a-divider orientation="left">审批操作</a-divider>
          <a-form layout="vertical">
            <a-form-item label="操作类型">
              <a-select v-model="action">
                <a-select-option v-for="item in actions" :key="item.value" :value="item.value">
                  {{ item.label }}
                </a-select-option>
              </a-select>
            </a-form-item>
            <a-form-item v-if="needAssignee" label="目标审批人（用户ID或用户名）">
              <a-input v-model="assignee" placeholder="例如 u_manager / manager" />
            </a-form-item>
            <a-form-item v-if="action === 'jump'" label="目标节点定义 Key">
              <a-input v-model="targetTaskDefinitionKey" placeholder="例如 deptApprove" />
            </a-form-item>
            <a-form-item v-if="needComment" label="审批意见">
              <a-textarea v-model="comment" :rows="3" placeholder="请输入审批意见" />
            </a-form-item>
          </a-form>
        </template>
      </div>
    </a-spin>

    <div
      v-if="canApprove"
      style="position: absolute; bottom: 0; left: 0; right: 0; padding: 12px 24px; background: #fff; border-top: 1px solid #f0f0f0; text-align: right"
    >
      <a-button style="margin-right: 8px" @click="close">取消</a-button>
      <a-button type="primary" :loading="submitting" @click="submit">提交</a-button>
    </div>
  </a-drawer>
</template>

<script>
import { api } from '../api';

export default {
  name: 'ApprovalDrawer',
  props: {
    visible: { type: Boolean, default: false },
    record: { type: Object, default: null },
    canApprove: { type: Boolean, default: false }
  },
  data() {
    return {
      loading: false,
      submitting: false,
      history: [],
      pic: '',
      action: 'pass',
      comment: '',
      assignee: '',
      targetTaskDefinitionKey: '',
      actions: [
        { value: 'pass', label: '审批通过' },
        { value: 'turnTo', label: '转办（转给他人后由他人继续）' },
        { value: 'appoint', label: '委派（他人处理后回到自己）' },
        { value: 'jump', label: '跳转到指定节点' },
        { value: 'claim', label: '认领任务' },
        { value: 'urge', label: '催办' },
        { value: 'repeal', label: '撤销流程' },
        { value: 'terminal', label: '终止流程' }
      ]
    };
  },
  computed: {
    businessKey() {
      if (!this.record) {
        return '';
      }
      return this.record.businessKey || (this.record.businessData && this.record.businessData.businessKey) || '';
    },
    processName() {
      if (!this.record) {
        return '-';
      }
      return (
        this.record.processName ||
        this.record.name ||
        (this.record.businessData && (this.record.businessData.defName || this.record.businessData.defKey)) ||
        '-'
      );
    },
    startUser() {
      if (!this.record) {
        return '-';
      }
      return this.record.startUserName || this.record.startUser || '-';
    },
    stateText() {
      const map = { '1': '运行中', '2': '已完成', '3': '已终止', '4': '已撤销' };
      return map[String(this.record && this.record.state)] || '未知';
    },
    stateColor() {
      const map = { '1': 'processing', '2': 'success', '3': 'error', '4': 'default' };
      return map[String(this.record && this.record.state)] || 'default';
    },
    variablesText() {
      if (!this.record) {
        return '{}';
      }
      let value = this.record.variables;
      if (!value && this.record.businessData) {
        value = this.record.businessData.variables;
      }
      if (typeof value === 'string') {
        try {
          value = JSON.parse(value);
        } catch (e) {
          return value;
        }
      }
      return JSON.stringify(value || {}, null, 2);
    },
    picSrc() {
      return this.pic ? `data:image/svg+xml;base64,${this.pic}` : '';
    },
    needAssignee() {
      return this.action === 'turnTo' || this.action === 'appoint' || this.action === 'jump';
    },
    needComment() {
      return ['pass', 'turnTo', 'appoint', 'jump', 'repeal', 'terminal'].indexOf(this.action) >= 0;
    }
  },
  watch: {
    visible(value) {
      if (value) {
        this.load();
      }
    }
  },
  methods: {
    load() {
      this.loading = true;
      this.history = [];
      this.pic = '';
      this.action = 'pass';
      this.comment = '';
      this.assignee = '';
      this.targetTaskDefinitionKey = '';
      if (!this.businessKey) {
        this.loading = false;
        return;
      }
      const requests = [
        api.get('/flow/model/getHis', { params: { businessKey: this.businessKey } }),
        api.get('/flow/model/getPic', { params: { businessKey: this.businessKey } })
      ];
      Promise.allSettled(requests)
        .then(results => {
          const his = results[0];
          if (his.status === 'fulfilled' && his.value.data && his.value.data.success !== false) {
            this.history = his.value.data.result || [];
          }
          const pic = results[1];
          if (pic.status === 'fulfilled' && pic.value.data && pic.value.data.success !== false) {
            this.pic = pic.value.data.message || '';
          }
        })
        .finally(() => {
          this.loading = false;
        });
    },
    submit() {
      if (!this.record) {
        return;
      }
      if (this.needAssignee && !this.assignee) {
        this.$message.warning('请填写目标审批人');
        return;
      }
      if (this.action === 'jump' && !this.targetTaskDefinitionKey) {
        this.$message.warning('请填写目标节点 Key');
        return;
      }
      const payload = {
        taskId: this.record.taskId || this.record.id,
        comment: this.comment
      };
      if (this.needAssignee) {
        payload.assignee = this.assignee;
      }
      if (this.action === 'jump') {
        payload.targetTaskDefinitionKey = this.targetTaskDefinitionKey;
      }
      this.submitting = true;
      api
        .post(`/flow/${this.action}`, payload)
        .then(response => {
          const body = response.data || {};
          if (body.success === false) {
            this.$message.error(body.message || '操作失败');
            return;
          }
          this.$message.success(body.message || '操作成功');
          this.$emit('done');
          this.close();
        })
        .catch(() => {
          this.$message.error('操作失败，请稍后重试');
        })
        .finally(() => {
          this.submitting = false;
        });
    },
    close() {
      this.$emit('update:visible', false);
    }
  }
};
</script>
