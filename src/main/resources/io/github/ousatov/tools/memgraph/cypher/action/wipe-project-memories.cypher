MATCH (n)
WHERE n.project = $project
  AND (
    n:Memory
    OR n:Decision
    OR n:Idea
    OR n:Context
    OR n:Rule
    OR n:Task
    OR n:Finding
    OR n:Question
    OR n:Risk
    OR n:ADR
    OR n:CodeRef
  )
DETACH DELETE n
