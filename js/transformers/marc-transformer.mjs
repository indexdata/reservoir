export function transform(clusterStr) {
  let cluster = JSON.parse(clusterStr);
  let recs = cluster.records;
  //merge all marc recs
  const out = {};
  out.leader = 'new leader';
  out.fields = [];
  for (let i = 0; i < recs.length; i++) {
    let rec = recs[i];
    let marc = rec.payload.marc;
    //collect all marc fields
    out.fields.push(...marc.fields);
    //stamp with custom 999 for each member
    out.fields.push(
      {
        '999' : 
        {
          'ind1': '1',
          'ind2': '0',
          'subfields': [
            {'i': rec.globalId },
            {'l': rec.localId },
            {'s': rec.sourceId }
          ]
        }
      }
    );
  }
  return JSON.stringify(out);
}
