<template>
  <div class="page-card">
    <div class="toolbar">
      <a-button icon="arrow-left" @click="$router.push('/templates')">返回</a-button>
      <span style="font-weight: 600; font-size: 16px">{{ model.name || '流程设计器' }}</span>
      <span class="muted">Key：{{ model.key }}</span>
      <a-tag v-if="model.version >= 1" color="orange">已发布，仅供查看</a-tag>
      <a-button icon="fullscreen" @click="fit">适应画布</a-button>
      <a-button icon="code" @click="openXml">XML 源码</a-button>
      <a-button type="primary" icon="save" :loading="saving" @click="save(false)">保存</a-button>
      <a-button type="primary" icon="cloud-upload" :loading="publishing" @click="publish">发布</a-button>
    </div>

    <a-spin :spinning="loading">
      <div class="designer-page">
        <div class="designer-canvas" ref="canvas"></div>
        <a-card class="designer-panel" size="small" title="元素属性">
          <a-empty v-if="!selected" description="请在画布中选择一个元素" />
          <a-form v-else layout="vertical">
            <a-form-item label="元素 ID">
              <a-input :value="selected.id" disabled />
            </a-form-item>
            <a-form-item label="元素类型">
              <a-input :value="selectedType" disabled />
            </a-form-item>
            <a-form-item label="名称">
              <a-input v-model="props.name" placeholder="节点名称" />
            </a-form-item>
            <template v-if="isUserTask">
              <a-form-item label="审批人（用户ID或用户名）">
                <a-input v-model="props.assignee" placeholder="u_manager / manager" />
              </a-form-item>
              <a-form-item label="候选用户（逗号分隔）">
                <a-input v-model="props.candidateUsers" placeholder="u_zhangsan,u_lisi" />
              </a-form-item>
              <a-form-item label="候选角色（逗号分隔）">
                <a-input v-model="props.candidateGroups" placeholder="manager,finance" />
              </a-form-item>
              <a-form-item label="表单 Key">
                <a-input v-model="props.formKey" placeholder="可选" />
              </a-form-item>
            </template>
            <template v-if="isSequenceFlow">
              <a-form-item label="条件表达式">
                <a-textarea v-model="props.condition" :rows="3" placeholder="${amount > 1000}" />
              </a-form-item>
            </template>
            <a-form-item label="说明">
              <a-textarea v-model="props.documentation" :rows="2" placeholder="节点说明" />
            </a-form-item>
            <a-button type="primary" block icon="check" @click="applyProps">应用属性</a-button>
          </a-form>
        </a-card>
      </div>
    </a-spin>

    <a-modal
      title="BPMN XML 源码"
      :visible="xmlVisible"
      width="900px"
      ok-text="应用 XML"
      @ok="applyXml"
      @cancel="xmlVisible = false"
    >
      <a-textarea v-model="xmlText" :rows="24" style="font-family: Consolas, monospace" />
    </a-modal>
  </div>
</template>

<script>
import BpmnModeler from 'bpmn-js/lib/Modeler';
import flowModdle from '../bpmn/flow-moddle';
import { api } from '../api';

export default {
  name: 'Designer',
  data() {
    return {
      loading: true,
      saving: false,
      publishing: false,
      model: {},
      modeler: null,
      selected: null,
      props: {
        name: '',
        assignee: '',
        candidateUsers: '',
        candidateGroups: '',
        formKey: '',
        condition: '',
        documentation: ''
      },
      xmlVisible: false,
      xmlText: ''
    };
  },
  computed: {
    modelId() {
      return this.$route.params.id;
    },
    isUserTask() {
      return !!this.selected && this.selectedType === 'bpmn:UserTask';
    },
    isSequenceFlow() {
      return !!this.selected && this.selectedType === 'bpmn:SequenceFlow';
    },
    selectedType() {
      if (!this.selected) {
        return '';
      }
      if (this.selected.businessObject && this.selected.businessObject.$type) {
        return this.selected.businessObject.$type;
      }
      return this.selected.type || '';
    }
  },
  mounted() {
    this.loadModel();
  },
  beforeDestroy() {
    if (this.modeler) {
      this.modeler.destroy();
      this.modeler = null;
    }
  },
  methods: {
    loadModel() {
      this.loading = true;
      api
        .get('/act/model/queryById', { params: { key: this.modelId } })
        .then(response => {
          const body = response.data || {};
          if (body.success === false || !body.result) {
            this.$message.error(body.message || '模板不存在');
            return;
          }
          this.model = body.result;
          this.$nextTick(() => this.initModeler(this.model.bpmnXml || ''));
        })
        .catch(() => this.$message.error('加载模板失败'))
        .finally(() => {
          this.loading = false;
        });
    },
    initModeler(xml) {
      this.modeler = new BpmnModeler({
        container: this.$refs.canvas,
        moddleExtensions: { flow: flowModdle }
      });
      this.modeler.on('selection.changed', event => {
        const element = event.newSelection && event.newSelection[0];
        this.selectElement(element || null);
      });
      this.modeler
        .importXML(xml)
        .then(() => this.fit())
        .catch(error => {
          this.$message.error('BPMN 解析失败：' + (error.message || error));
        });
    },
    selectElement(element) {
      this.selected = element;
      if (!element) {
        return;
      }
      const bo = element.businessObject || {};
      const attrs = bo.$attrs || {};
      this.props = {
        name: bo.name || '',
        assignee: bo.assignee || attrs['flow:assignee'] || attrs.assignee || '',
        candidateUsers: bo.candidateUsers || attrs['flow:candidateUsers'] || attrs.candidateUsers || '',
        candidateGroups: bo.candidateGroups || attrs['flow:candidateGroups'] || attrs.candidateGroups || '',
        formKey: bo.formKey || attrs['flow:formKey'] || attrs.formKey || '',
        condition: bo.conditionExpression ? bo.conditionExpression.body || '' : '',
        documentation: bo.documentation && bo.documentation.length ? bo.documentation[0].text || '' : ''
      };
    },
    applyProps() {
      if (!this.selected || !this.modeler) {
        return;
      }
      const modeling = this.modeler.get('modeling');
      const moddle = this.modeler.get('moddle');
      const element = this.selected;
      const properties = { name: this.props.name || undefined };

      if (this.isUserTask) {
        properties.assignee = this.props.assignee || undefined;
        properties.candidateUsers = this.props.candidateUsers || undefined;
        properties.candidateGroups = this.props.candidateGroups || undefined;
        properties.formKey = this.props.formKey || undefined;
      }
      if (this.isSequenceFlow) {
        properties.conditionExpression = this.props.condition
          ? moddle.create('bpmn:FormalExpression', { body: this.props.condition })
          : undefined;
      }
      properties.documentation = this.props.documentation
        ? [moddle.create('bpmn:Documentation', { text: this.props.documentation })]
        : undefined;

      try {
        modeling.updateProperties(element, properties);
        this.$message.success('属性已更新');
      } catch (e) {
        this.$message.error('属性更新失败：' + (e.message || e));
      }
    },
    save(silent) {
      if (!this.modeler) {
        return Promise.resolve(false);
      }
      this.saving = true;
      return this.modeler
        .saveXML({ format: true })
        .then(({ xml }) => {
          this.xmlText = xml;
          const payload = {
            id: this.model.id,
            name: this.model.name,
            key: this.model.key,
            category: this.model.category,
            description: this.model.description,
            bpmnXml: xml
          };
          return api.put('/act/model/edit', payload).then(response => {
            const body = response.data || {};
            if (body.success === false) {
              this.$message.error(body.message || '保存失败');
              return false;
            }
            if (!silent) {
              this.$message.success('保存成功');
            }
            return true;
          });
        })
        .catch(error => {
          this.$message.error('保存失败：' + (error.message || error));
          return false;
        })
        .finally(() => {
          this.saving = false;
        });
    },
    publish() {
      const target = this.model;
      if (target.version >= 1) {
        this.$message.warning('已发布的流程定义不能再次发布');
        return;
      }
      this.publishing = true;
      this.save(true)
        .then(saved => {
          if (!saved) {
            return null;
          }
          return api.get('/act/model/deploy', { params: { id: target.id } });
        })
        .then(response => {
          if (!response) {
            return;
          }
          const body = response.data || {};
          if (body.success === false) {
            this.$message.error(body.message || '发布失败');
            return;
          }
          this.$message.success('发布成功，可在流程目录中发起');
          this.$router.push('/templates');
        })
        .catch(() => this.$message.error('发布失败'))
        .finally(() => {
          this.publishing = false;
        });
    },
    fit() {
      if (!this.modeler) {
        return;
      }
      try {
        const canvas = this.modeler.get('canvas');
        const viewbox = canvas.viewbox();
        const outer = viewbox.outer;
        const inner = viewbox.inner;
        if (!inner.width || !inner.height) {
          canvas.zoom('fit-viewport');
          return;
        }
        // 左侧留出 palette 的宽度，避免开始节点及其标签被工具栏遮挡
        const padding = { top: 24, right: 24, bottom: 24, left: 120 };
        const availableWidth = Math.max(outer.width - padding.left - padding.right, 120);
        const availableHeight = Math.max(outer.height - padding.top - padding.bottom, 120);
        const scale = Math.min(1, availableWidth / inner.width, availableHeight / inner.height);
        const offsetX = padding.left + Math.max(0, (availableWidth - inner.width * scale) / 2);
        const offsetY = padding.top + Math.max(0, (availableHeight - inner.height * scale) / 2);
        canvas.viewbox({
          x: inner.x - offsetX / scale,
          y: inner.y - offsetY / scale,
          width: outer.width / scale,
          height: outer.height / scale
        });
      } catch (e) {
        // ignore
      }
    },
    openXml() {
      if (!this.modeler) {
        return;
      }
      this.modeler
        .saveXML({ format: true })
        .then(({ xml }) => {
          this.xmlText = xml;
          this.xmlVisible = true;
        })
        .catch(() => this.$message.error('读取 XML 失败'));
    },
    applyXml() {
      if (!this.modeler) {
        return;
      }
      this.modeler
        .importXML(this.xmlText)
        .then(() => {
          this.xmlVisible = false;
          this.fit();
          this.$message.success('XML 已应用，请记得保存');
        })
        .catch(error => this.$message.error('XML 解析失败：' + (error.message || error)));
    }
  }
};
</script>
