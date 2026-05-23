MATCH (n:PendingCall {project: $project})
RETURN count(n) AS value
