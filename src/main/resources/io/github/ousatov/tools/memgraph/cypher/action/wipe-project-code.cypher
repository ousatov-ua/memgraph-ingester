MATCH (n)
WHERE n.project = $project
  AND (
    n:Code
    OR n:Package
    OR n:File
    OR n:Class
    OR n:Interface
    OR n:Annotation
    OR n:Method
    OR n:Field
  )
DETACH DELETE n
