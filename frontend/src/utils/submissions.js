import api from '../api/axios'

/**
 * Saves a submitted file (GET /submissions/{id}/download, always an attachment). Throws the request error; callers
 * show it with getBlobErrorMessage, since the error body comes as a Blob.
 */
export async function downloadSubmission(item) {
  const { data } = await api.get(`/submissions/${item.id}/download`, { responseType: 'blob', timeout: 0 })
  const url = URL.createObjectURL(data)
  const link = document.createElement('a')
  link.href = url
  link.download = item.originalFileName
  link.click()
  URL.revokeObjectURL(url)
}
