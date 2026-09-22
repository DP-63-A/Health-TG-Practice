let sessionToken: string | null = null

export function setSessionToken(token: string | null) {
  sessionToken = token
}

export function getSessionToken() {
  return sessionToken
}

export function clearSessionToken() {
  sessionToken = null
}
