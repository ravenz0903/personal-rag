import React, { useState } from 'react';

export default function TaskParentSelect({ tasks, task, parent }) {
  const [value, setValue] = useState(parent?.id || task?.parentId || '');
  const options = tasks.filter(candidate => !candidate.archived && !candidate.parentId && candidate.id !== task?.id);
  const missingSelection = value && !options.some(candidate => candidate.id === value);
  return <select name="parentId" value={value} onChange={event => setValue(event.target.value)}>
    <option value="">None</option>
    {missingSelection && <option value={value}>{parent?.title || 'Current parent'}</option>}
    {options.map(candidate => <option key={candidate.id} value={candidate.id}>{candidate.title} · {candidate.domain.toLowerCase()}</option>)}
  </select>;
}
