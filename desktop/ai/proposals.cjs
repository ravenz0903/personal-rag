// Experimental preflight only. Trusted context must come from the backend resolver.
// This does not execute proposals or replace backend authorization/user review.
const areas = ['ACADEMICS', 'CAREER', 'PERSONAL'];
const priorities = ['LOW', 'MEDIUM', 'HIGH', 'URGENT'];
const fields = {
  create_task: ['action','area','title','priority','dueLocal','remindLocal'],
  edit_task: ['action','recordId','title','priority','dueLocal','remindLocal'],
  create_event: ['action','area','title','startLocal','endLocal'],
  answer: ['action','answer','citations','abstain'],
  clarify: ['action','answer'], unsupported: ['action','answer'],
};
function validDate(value) {
  if(typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/.test(value)) return false;
  const parsed = new Date(value + ':00Z');
  return Number.isFinite(parsed.getTime()) && parsed.toISOString().slice(0,16) === value;
}
function checkProposal(proposal, context) {
  const errors=[];
  const scoped = record => record && record.workspaceId === context.workspaceId &&
    areas.includes(record.area) && (context.area === 'ALL' ? record.area !== 'PERSONAL' : record.area === context.area);
  if(!context || typeof context.workspaceId !== 'string' || !context.workspaceId.trim() ||
      !['ALL',...areas].includes(context.area) || !['retrieval','actions'].includes(context.mode)) return ['missing trusted scope'];
  if(!proposal || typeof proposal !== 'object' || Array.isArray(proposal) || !Object.hasOwn(fields,proposal.action)) return ['invalid proposal'];
  const allowed = fields[proposal.action];
  for(const key of Object.keys(proposal)) if(!allowed.includes(key)) errors.push('unexpected field: '+key);
  const writing = ['create_task','edit_task','create_event'].includes(proposal.action);
  if(writing && context.mode === 'retrieval') errors.push('retrieval cannot write');
  if(['create_task','create_event'].includes(proposal.action) && !scoped({workspaceId:context.workspaceId,area:proposal.area})) errors.push('area outside scope');
  if(['create_task','create_event'].includes(proposal.action) && (typeof proposal.title !== 'string' || !proposal.title.trim() || proposal.title.length>300)) errors.push('invalid title');
  if(Object.hasOwn(proposal,'priority') && !priorities.includes(proposal.priority)) errors.push('invalid priority');
  if(Object.hasOwn(proposal,'dueLocal') && !validDate(proposal.dueLocal)) errors.push('invalid deadline');
  if(Object.hasOwn(proposal,'remindLocal') && !validDate(proposal.remindLocal)) errors.push('invalid reminder');
  if(context.reminderIntent && proposal.action==='create_task' && !Object.hasOwn(proposal,'remindLocal'))errors.push('missing actual reminder');
  if(proposal.action === 'create_event' && (!validDate(proposal.startLocal) || !validDate(proposal.endLocal) || proposal.endLocal <= proposal.startLocal)) errors.push('invalid event range');
  if(proposal.action === 'edit_task') {
    const matches = context.matchedRecords;
    if(!Array.isArray(matches) || matches.length !== 1 || !scoped(matches[0]) || matches[0].id !== proposal.recordId) errors.push('unresolved or inaccessible record');
    if(!['title','priority','dueLocal','remindLocal'].some(key=>Object.hasOwn(proposal,key))) errors.push('empty edit');
    if(Object.hasOwn(proposal,'title') && (typeof proposal.title !== 'string' || !proposal.title.trim() || proposal.title.length>300)) errors.push('invalid title');
  }
  if(['answer','clarify','unsupported'].includes(proposal.action) && (typeof proposal.answer !== 'string' || !proposal.answer.trim() || proposal.answer.length>12000)) errors.push('invalid answer text');
  if(proposal.action === 'answer') {
    if(typeof proposal.abstain !== 'boolean') errors.push('missing abstention flag');
    if(!Array.isArray(proposal.citations) || proposal.citations.some(id=>typeof id !== 'string') || new Set(proposal.citations).size !== proposal.citations.length) errors.push('invalid citations');
    else {
      const sources = Array.isArray(context.sources) ? context.sources : [];
      for(const id of proposal.citations) if(!sources.some(source=>source?.id===id && scoped(source))) errors.push('inaccessible citation');
      if(proposal.abstain === false && !proposal.citations.length) errors.push('ungrounded answer');
      if(proposal.abstain === true && proposal.citations.length) errors.push('abstention must not claim support');
    }
  }
  return errors;
}
module.exports={checkProposal,validDate};
