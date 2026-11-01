/**
 * 自定义 BPMN 扩展：把审批人、候选用户/角色等属性写入 flow 命名空间，
 * 后端 BpmnXmlParser 会按属性 localName 读取。
 */
export default {
  name: 'Flow',
  uri: 'http://joinsoft.com/flow',
  prefix: 'flow',
  xml: {
    tagAlias: 'lowerCase'
  },
  associations: [],
  types: [
    {
      name: 'UserTask',
      extends: ['bpmn:UserTask'],
      properties: [
        { name: 'assignee', isAttr: true, type: 'String' },
        { name: 'candidateUsers', isAttr: true, type: 'String' },
        { name: 'candidateGroups', isAttr: true, type: 'String' },
        { name: 'formKey', isAttr: true, type: 'String' }
      ]
    },
    {
      name: 'UserFilter',
      superClass: ['Element'],
      properties: [{ name: 'value', type: 'String', isBody: true }]
    },
    {
      name: 'AutoPass',
      superClass: ['Element'],
      properties: [{ name: 'value', type: 'String', isBody: true }]
    },
    {
      name: 'NoticeStartUser',
      superClass: ['Element'],
      properties: [{ name: 'value', type: 'String', isBody: true }]
    },
    {
      name: 'NoticeApprovalUser',
      superClass: ['Element'],
      properties: [{ name: 'value', type: 'String', isBody: true }]
    }
  ]
};
