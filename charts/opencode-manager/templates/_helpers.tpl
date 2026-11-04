{{- define "opencode-manager.fullname" -}}
{{- if contains .Chart.Name .Release.Name -}}
{{- .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s-%s" .Release.Name .Chart.Name | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}

{{- define "opencode-manager.selectorLabels" -}}
app.kubernetes.io/name: {{ .Chart.Name }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{- define "opencode-manager.labels" -}}
{{ include "opencode-manager.selectorLabels" . }}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end -}}

{{- define "opencode-manager.image" -}}
{{- printf "%s:%s" .Values.image.repository (default .Chart.AppVersion .Values.image.tag) -}}
{{- end -}}

{{- define "opencode-manager.sharedPvc" -}}
{{- printf "%s-shared" (include "opencode-manager.fullname" .) -}}
{{- end -}}

{{- define "opencode-manager.forwardAuth" -}}
{{- printf "%s-forward-auth" (include "opencode-manager.fullname" .) -}}
{{- end -}}

{{- define "opencode-manager.gatewayParentRef" -}}
- name: {{ .Values.gateway.name }}
  {{- with .Values.gateway.namespace }}
  namespace: {{ . }}
  {{- end }}
  {{- with .Values.gateway.sectionName }}
  sectionName: {{ . }}
  {{- end }}
{{- end -}}
