import { FormControlLabel, MenuItem, Stack, Switch, TextField } from "@mui/material";
import type { JsonSchema } from "../api";

const LONG_TEXT = new Set(["prompt", "task", "brief"]);

function resolve(schema: JsonSchema): JsonSchema {
  const options = schema.anyOf?.filter((option) => option.type !== "null");
  return options?.length === 1 ? { ...options[0], description: schema.description, default: schema.default } : schema;
}

function label(name: string, schema: JsonSchema): string {
  return schema.title ?? name.replace(/_/g, " ").replace(/^\w/, (c) => c.toUpperCase());
}

interface Props {
  schema: JsonSchema;
  value: Record<string, unknown>;
  onChange: (value: Record<string, unknown>) => void;
}

export default function SchemaForm({ schema, value, onChange }: Props) {
  const required = new Set(schema.required ?? []);
  const fields = Object.entries(schema.properties ?? {}).map(([name, raw]) => [name, resolve(raw)] as const);
  const set = (name: string, next: unknown) => onChange({ ...value, [name]: next === "" ? undefined : next });

  return (
    <Stack spacing={2}>
      {fields.map(([name, field]) => {
        const current = value[name] ?? "";
        const common = {
          key: name,
          label: label(name, field),
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
