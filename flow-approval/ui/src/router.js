import Vue from 'vue';
import VueRouter from 'vue-router';
import { tokenStore } from './api';
import Login from './views/Login.vue';
import MainLayout from './layout/MainLayout.vue';
import TaskList from './views/TaskList.vue';
import ProcessCatalog from './views/ProcessCatalog.vue';
import Templates from './views/Templates.vue';
import Definitions from './views/Definitions.vue';
import Designer from './views/Designer.vue';

Vue.use(VueRouter);

const router = new VueRouter({
  mode: 'hash',
  routes: [
    { path: '/login', component: Login, meta: { public: true } },
    {
      path: '/',
      component: MainLayout,
      children: [
        { path: '', redirect: '/todo' },
        { path: 'todo', component: TaskList, meta: { kind: 'todo', title: '待办任务' } },
        { path: 'done', component: TaskList, meta: { kind: 'done', title: '已办任务' } },
        { path: 'mine', component: TaskList, meta: { kind: 'mine', title: '我的申请' } },
        { path: 'all', component: TaskList, meta: { kind: 'all', title: '全部流程' } },
        { path: 'catalog', component: ProcessCatalog, meta: { title: '流程目录' } },
        { path: 'templates', component: Templates, meta: { title: '流程模板' } },
        { path: 'definitions', component: Definitions, meta: { title: '流程定义' } },
        { path: 'designer/:id', component: Designer, meta: { title: '流程设计器' } }
      ]
    }
  ]
});

router.beforeEach((to, from, next) => {
  if (to.meta && to.meta.public) {
    next();
    return;
  }
  if (!tokenStore.get()) {
    next({ path: '/login', query: { redirect: to.fullPath } });
    return;
  }
  next();
});

export default router;
