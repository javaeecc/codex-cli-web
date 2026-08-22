import axios from 'axios'

const client = axios.create({ baseURL: '/api', timeout: 30000, withCredentials: true })
const urlToken = new URLSearchParams(location.search).get('token')
if (urlToken) client.defaults.headers.common['X-Codex-Token'] = urlToken
if (urlToken) {
  const url = new URL(location.href)
  url.searchParams.delete('token')
  window.history.replaceState({}, document.title, `${url.pathname}${url.search}${url.hash}`)
}
const AUTH_EXPIRED_EVENT = 'codex-web-auth-expired'

client.interceptors.response.use(response => response, error => {
  const status = error && error.response && error.response.status
  const url = error && error.config && error.config.url ? error.config.url : ''
  if (status === 401 && !url.includes('/auth/login') && !url.includes('/auth/logout') && !url.includes('/health')) window.dispatchEvent(new Event(AUTH_EXPIRED_EVENT))
  return Promise.reject(error)
})

export default {
  onAuthExpired: handler => { window.addEventListener(AUTH_EXPIRED_EVENT, handler); return () => window.removeEventListener(AUTH_EXPIRED_EVENT, handler) },
  notifyAuthExpired: () => window.dispatchEvent(new Event(AUTH_EXPIRED_EVENT)),
  authMe: () => client.get('/auth/me'),
  login: payload => client.post('/auth/login', payload),
  logout: () => client.post('/auth/logout'),
  health: () => client.get('/health'),
  runtime: () => client.get('/runtime'),
  startRuntime: () => client.post('/runtime/start'),
  settings: () => client.get('/settings'),
  updateSettings: payload => client.put('/settings', payload),
  projects: () => client.get('/projects'),
  project: id => client.get(`/projects/${id}`),
  createProject: payload => client.post('/projects', payload),
  updateProject: (id, payload) => client.put(`/projects/${id}`, payload),
  deleteProject: id => client.delete(`/projects/${id}`),
  sessions: projectId => client.get(`/projects/${projectId}/sessions`),
  allSessions: () => client.get('/sessions'),
  createSession: (projectId, payload) => client.post(`/projects/${projectId}/sessions`, payload),
  session: id => client.get(`/sessions/${id}`),
  events: (id, afterEventId) => client.get(`/sessions/${id}/events`, { params: afterEventId ? { after: afterEventId } : {} }),
  history: (id, config) => client.get(`/sessions/${id}/history`, config),
  streamUrl: id => `/api/sessions/${encodeURIComponent(id)}/stream`,
  streamHeaders: () => urlToken ? { 'X-Codex-Token': urlToken } : {},
  startTurn: (id, payload) => client.post(`/sessions/${id}/turns`, payload),
  steerTurn: (id, payload) => client.post(`/sessions/${id}/steer`, payload),
  steerQueued: (id, queueId) => client.post(`/sessions/${id}/queue/${queueId}/steer`),
  deleteQueued: (id, queueId) => client.delete(`/sessions/${id}/queue/${queueId}`),
  cancelTurn: id => client.post(`/sessions/${id}/cancel`),
  respondApproval: (id, payload) => client.post(`/sessions/${id}/approval`, payload),
  exportSession: id => client.get(`/sessions/${id}/export`),
  updateSession: (id, payload) => client.put(`/sessions/${id}`, payload),
  archive: id => client.post(`/sessions/${id}/archive`),
  unarchive: id => client.post(`/sessions/${id}/unarchive`),
  deleteSession: id => client.delete(`/sessions/${id}`),
  roots: () => client.get('/workspaces/roots'),
  browse: path => client.get('/workspaces', { params: { path } }),
  createFolder: payload => client.post('/workspaces', payload),
  gitStatus: id => client.get(`/projects/${id}/git/status`),
  branches: id => client.get(`/projects/${id}/git/branches`),
  checkout: (id, branch) => client.post(`/projects/${id}/git/checkout`, { branch }),
  diff: (id, file) => client.get(`/projects/${id}/git/diff`, { params: file ? { file } : {} }),
  files: (id, path) => client.get(`/projects/${id}/files`, { params: path ? { path } : {} }),
  content: (id, path) => client.get(`/projects/${id}/files/content`, { params: { path } })
  ,upload: (id, file) => { const form = new FormData(); form.append('file', file); return client.post(`/sessions/${id}/uploads`, form, { headers: { 'Content-Type': 'multipart/form-data' } }) }
}
