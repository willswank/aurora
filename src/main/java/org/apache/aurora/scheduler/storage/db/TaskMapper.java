/**
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.aurora.scheduler.storage.db;

import java.util.List;
import java.util.Set;

import org.apache.aurora.gen.TaskQuery;
import org.apache.aurora.scheduler.storage.db.views.AssignedPort;
import org.apache.aurora.scheduler.storage.db.views.ScheduledTaskWrapper;
import org.apache.aurora.scheduler.storage.entities.ITaskEvent;
import org.apache.ibatis.annotations.Param;

/**
 * MyBatis mapper for scheduled tasks.
 */
interface TaskMapper {

  /**
   * Inserts a scheduled task.
   *
   * @param task Task to insert.
   */
  void insertScheduledTask(ScheduledTaskWrapper task);

  /**
   * Gets tasks based on a query.
   *
   * @param query Query to use as a filter for tasks.
   * @return Tasks matching the query.
   */
  List<ScheduledTaskWrapper> select(TaskQuery query);

  /**
   * Inserts the task events association within an
   * {@link org.apache.aurora.scheduler.storage.entities.IScheduledTask}.
   *
   * @param taskRowId Task row ID.
   * @param events Events to insert.
   */
  void insertTaskEvents(
      @Param("taskRowId") long taskRowId,
      @Param("events") List<ITaskEvent> events);

  /**
   * Inserts the assigned ports association within an
   * {@link org.apache.aurora.scheduler.storage.entities.IScheduledTask}.
   *
   * @param taskRowId Task row ID.
   * @param ports Assigned ports to insert.
   */
  void insertPorts(@Param("taskRowId") long taskRowId, @Param("ports") List<AssignedPort> ports);

  /**
   * Selects the assigned ports association within an
   * {@link org.apache.aurora.scheduler.storage.entities.IScheduledTask}.
   *
   * @param taskRowId Task row ID.
   * @return Ports associated with the task.
   */
  List<AssignedPort> selectPorts(@Param("taskRowId") long taskRowId);

  /**
   * Deletes all task rows.
   */
  void truncate();

  /**
   * Deletes task rows by ID.
   *
   * @param taskIds IDs of tasks to delete.
   */
  void deleteTasks(@Param("taskIds") Set<String> taskIds);
}
