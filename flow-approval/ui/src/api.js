import axios from 'axios';

const TOKEN_KEY = 'flow_approval_token';
const USER_KEY = 'flow_approval_user';

export const tokenStore = {
  get() {
    return localStorage.getItem(TOKEN_KEY);
  },
  get user() {
    const value = localStorage.getItem(USER_KEY);
    if (!value) {
      return null;
    }
    try {
      return JSON.parse(value);
    } catch (e) {
      return null;
    }
  },
  save(token, user) {
    localStorage.setItem(TOKEN_KEY, token);
    if (user) {
      localStorage.setItem(USER_KEY, JSON.stringify(user));
    }
  },
  clear() {
    localStorage.removeItem(TOKEN_KEY);
    localStorage.removeItem(USER_KEY);
  }
};

export const api = axios.create({
  baseURL: '',
  timeout: 30000
});

api.interceptors.request.use(config => {
  const token = tokenStore.get();
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

api.interceptors.response.use(
  response => response,
  error => {
    if (error.response && error.response.status === 401) {
      tokenStore.clear();
      if (window.location.hash.indexOf('#/login') !== 0) {
        window.location.hash = '#/login';
      }
    }
    return Promise.reject(error);
  }
);

/** 统一取出 Result.result */
export function dataOf(response) {
  const body = response && response.data ? response.data : {};
  return body.result;
}

/** 成功返回 true，否则弹出后端 message */
export function ensureOk(response, Vue) {
  const body = response && response.data ? response.data : {};
  if (body.success === false) {
    const message = body.message || '操作失败';
    if (Vue && Vue.$message) {
      Vue.$message.error(message);
    }
    return false;
  }
  return true;
}
