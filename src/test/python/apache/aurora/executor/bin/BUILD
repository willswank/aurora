#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

python_test_suite(
  name = 'all',
  dependencies = [
    ':gc_executor_entry_point',
    ':thermos_executor_entry_point',
  ]
)

python_tests(
  name = 'gc_executor_entry_point',
  sources = ['test_gc_executor_entry_point.py'],
  dependencies = [
    'src/main/python/apache/aurora/executor/bin:gc_executor_source',
  ],
)

python_tests(
  name = 'thermos_executor_entry_point',
  sources = ['test_thermos_executor_entry_point.py'],
  dependencies = [
    'src/main/python/apache/aurora/executor/bin:thermos_executor_source',
  ],
)
