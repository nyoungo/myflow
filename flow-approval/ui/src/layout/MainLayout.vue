<template>
  <a-layout style="min-height: 100vh">
    <a-layout-sider v-model="collapsed" collapsible :trigger="null" theme="dark" :width="216">
      <div class="layout-logo">FLOW APPROVAL</div>
      <a-menu theme="dark" mode="inline" :selected-keys="selectedKeys" @click="handleMenu">
        <a-menu-item v-for="item in menus" :key="item.path">
          <a-icon :type="item.icon" />
          <span>{{ item.title }}</span>
        </a-menu-item>
      </a-menu>
    </a-layout-sider>
    <a-layout>
      <a-layout-header class="layout-header">
        <div style="display: flex; align-items: center; gap: 16px">
          <a-icon
            :type="collapsed ? 'menu-unfold' : 'menu-fold'"
            style="font-size: 18px; cursor: pointer"
            @click="collapsed = !collapsed"
          />
          <span style="font-size: 16px; font-weight: 600">{{ pageTitle }}</span>
        </div>
        <div class="layout-user">
          <a-badge status="processing" />
          <span>{{ displayName }}</span>
          <a-dropdown>
            <a class="ant-dropdown-link" href="javascript:;">
              <a-avatar size="small" icon="user" />
            </a>
          </a-dropdown>
          <a-button type="link" size="small" @click="logout">退出</a-button>
        </div>
      </a-layout-header>
      <a-layout-content class="layout-content">
        <router-view />
      </a-layout-content>
    </a-layout>
  </a-layout>
</template>

<script>
import { api, tokenStore } from '../api';

const MENUS = [
  { path: '/todo', title: '待办任务', icon: 'solution' },
  { path: '/done', title: '已办任务', icon: 'check-circle' },
  { path: '/mine', title: '我的申请', icon: 'profile' },
  { path: '/all', title: '全部流程', icon: 'unordered-list' },
  { path: '/catalog', title: '流程目录', icon: 'appstore' },
  { path: '/templates', title: '流程模板', icon: 'cluster' },
  { path: '/definitions', title: '流程定义', icon: 'partition' }
];

export default {
  name: 'MainLayout',
  data() {
    return {
      collapsed: false,
      menus: MENUS,
      currentUser: tokenStore.user || {}
    };
  },
  computed: {
    selectedKeys() {
      const path = this.$route.path;
      if (path.indexOf('/designer') === 0) {
        return ['/templates'];
      }
      return [path];
    },
    pageTitle() {
      return (this.$route.meta && this.$route.meta.title) || '审批中心';
    },
    displayName() {
      return this.currentUser.realname || this.currentUser.username || '未登录';
    }
  },
  mounted() {
    api
      .get('/auth/userInfo')
      .then(response => {
        const body = response.data || {};
        if (body.success !== false && body.result) {
          this.currentUser = body.result;
          const token = tokenStore.get();
          tokenStore.save(token, body.result);
        }
      })
      .catch(() => {});
  },
  methods: {
    handleMenu(event) {
      if (event.key !== this.$route.path) {
        this.$router.push(event.key);
      }
    },
    logout() {
      api
        .post('/auth/logout')
        .catch(() => {})
        .finally(() => {
          tokenStore.clear();
          this.$router.replace('/login');
        });
    }
  }
};
</script>
