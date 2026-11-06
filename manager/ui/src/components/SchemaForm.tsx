import { Autocomplete, FormControlLabel, MenuItem, Stack, Switch, TextField } from "@mui/material";
import type { JsonSchema, OpenPullRequest } from "../api";

const LONG_TEXT = new Set(["prompt", "task", "brief"]);

function resolve(schema: JsonSchema): JsonSchema {
  const options = schema.anyOf?.filter((option) => option.type !== "null");
  return options?.length === 1 ? { ...options[0], description: schema.description, default: schema.default } : schema;
}

function label(name: string): string {
  return name.replace(/_/g, " ").replace(/^\w/, (c) => c.toUpperCase());
}

function prLabel(pr: OpenPullRequest): string {
  return `#${pr.id} ${pr.title} (${pr.author})`;
}

interface Props {
  schema: JsonSchema;
  value: Record<string, unknown>;
  onChange: (value: Record<string, unknown>) => void;
  pullRequests?: OpenPullRequest[];
}

export default function SchemaForm({ schema, value, onChange, pullRequests }: Props) {
  const required = new Set(schema.required ?? []);
  const fields = Object.entries(schema.properties ?? {}).map(([name, raw]) => [name, resolve(raw)] as const);
  const set = (name: string, next: unknown) => onChange({ ...value, [name]: next === "" ? undefined : next });

  return (
    <Stack spacing={2}>
      {fields.map(([name, field]) => {
        const current = value[name] ?? "";
        const common = {
          key: name,
          label: label(name),
          helperText: field.description,
          required: required.has(name),
          fullWidth: true,
        };
        if (field.type === "boolean") {
          return (
            <FormControlLabel
              key={name}
              control={<Switch checked={Boolean(value[name] ?? field.default)} onChange={(e) => set(name, e.target.checked)} />}
              label={common.label}
            />
          );
        }
        if (field.enum) {
          return (
            <TextField {...common} select value={current || field.default || ""} onChange={(e) => set(name, e.target.value)}>
              {field.enum.map((option) => (
                <MenuItem key={option} value={option}>
                  {option}
                </MenuItem>
              ))}
            </TextField>
          );
        }
        if (name === "pull_request" && pullRequests && pullRequests.length > 0) {
          const selected = pullRequests.find((pr) => pr.id === value[name]) ?? null;
          return (
            <Autocomplete
              key={name}
              options={pullRequests}
              value={selected}
              getOptionLabel={prLabel}
              onChange={(_, pr) => set(name, pr ? pr.id : undefined)}
              renderInput={(props) => (
                <TextField {...props} label={common.label} helperText={field.description} required={common.required} />
              )}
            />
          );
        }
        if (field.type === "integer" || field.type === "number") {
          return (
            <TextField
              {...common}
              type="number"
              value={current}
              placeholder={field.default !== undefined ? String(field.default) : undefined}
              onChange={(e) => set(name, e.target.value === "" ? undefined : Number(e.target.value))}
            />
          );
        }
        const long = LONG_TEXT.has(name) || (field.maxLength ?? 0) > 500;
        return (
          <TextField
            {...common}
            value={current}
            multiline={long}
            minRows={long ? 5 : undefined}
            onChange={(e) => set(name, e.target.value)}
          />
        );
      })}
    </Stack>
  );
}
