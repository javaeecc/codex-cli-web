import axios from 'axios'

const client = axios.create({ baseURL: '/api', timeout: 30000, withCredentials: true })
const urlToken = new URLSearchParams(location.search).get('token')
if (urlToken) client.defaults.headers.common['X-Codex-Token'] = urlToken
export default {
  authMe: () => client.get('/auth/me'),
  login: payload => client.post('/auth/login', payload),
  logout: () => client.post('/auth/logout'),
  health: () => client.get('/health'),
  runtime: () => client.get('/runtime'),
  runtimeStart: () => client.post('/runtime/start'),
  runtimeStop: () => client.post('/runtime/stop'),
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
  streamUrl: id => { const token = new URLSearchParams(location.search).get('token'); return `/api/sessions/${encodeURIComponent(id)}/stream${token ? `?token=${encodeURIComponent(token)}` : ''}` },
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
