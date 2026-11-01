import Vue from 'vue';
import Antd from 'ant-design-vue';
import 'ant-design-vue/dist/antd.css';
import 'bpmn-js/dist/assets/diagram-js.css';
import 'bpmn-js/dist/assets/bpmn-font/css/bpmn.css';
import App from './App.vue';
import router from './router';
import './styles.css';

Vue.config.productionTip = false;
Vue.use(Antd);

new Vue({
  router,
  render: h => h(App)
}).$mount('#app');
