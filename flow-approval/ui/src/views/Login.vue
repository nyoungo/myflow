<template>
  <div class="login-page">
    <div class="login-card">
      <div class="login-title">Flow Approval</div>
      <div class="login-subtitle">轻量工作流审批中心</div>
      <a-form :form="form" @submit="handleSubmit">
        <a-form-item>
          <a-input
            v-decorator="['username', { rules: [{ required: true, message: '请输入用户名' }] }]"
            size="large"
            placeholder="用户名"
          >
            <a-icon slot="prefix" type="user" />
          </a-input>
        </a-form-item>
        <a-form-item>
          <a-input-password
            v-decorator="['password', { rules: [{ required: true, message: '请输入密码' }] }]"
            size="large"
            placeholder="密码"
          >
            <a-icon slot="prefix" type="lock" />
          </a-input-password>
        </a-form-item>
        <a-form-item>
          <a-button type="primary" html-type="submit" size="large" block :loading="loading">
            登录
          </a-button>
        </a-form-item>
      </a-form>
      <div class="muted" style="font-size: 12px; line-height: 20px">
        演示账号：admin / admin123、manager / manager123、zhangsan / 123456
      </div>
    </div>
  </div>
</template>

<script>
import { api, tokenStore } from '../api';

export default {
  name: 'LoginPage',
  data() {
    return {
      loading: false,
      form: this.$form.createForm(this, { name: 'login_form' })
    };
  },
  methods: {
    handleSubmit(e) {
      e.preventDefault();
      this.form.validateFields((err, values) => {
        if (err) {
          return;
        }
        this.loading = true;
        api
          .post('/auth/login', {
            username: values.username,
            password: values.password
          })
          .then(response => {
            const body = response.data || {};
            if (body.success === false) {
              this.$message.error(body.message || '登录失败');
              return;
            }
            const data = body.result || {};
            tokenStore.save(data.token, data.user);
            this.$message.success('登录成功');
            const redirect = this.$route.query.redirect || '/todo';
            this.$router.replace(redirect);
          })
          .catch(error => {
            const body = (error.response && error.response.data) || {};
            this.$message.error(body.message || '登录失败，请检查用户名和密码');
          })
          .finally(() => {
            this.loading = false;
          });
      });
    }
  }
};
</script>
