import React, { useState, useEffect } from 'react';
import { Button, Input, Alert, Card, Row, Col, Table, Tag, Collapse, Typography, Space, Spin } from 'antd';
import {
  SearchOutlined,
  DatabaseOutlined,
  CodeOutlined,
  ExclamationCircleOutlined,
  CheckCircleOutlined,
  BulbOutlined,
} from '@ant-design/icons';

const { TextArea } = Input;
const { Panel } = Collapse;
const { Title, Text, Paragraph } = Typography;

interface Column {
  name: string;
  display_name?: string;
  base_type: string;
}

interface ResponseData {
  data: {
    data: {
      rows: Array<Array<string | number | null>>;
      cols: Column[];
    };
  };
  sql?: string;
  availableTables?: string[];
  status: string;
  timestamp: number;
  error?: string;
}

interface TableMismatch {
  message: string;
  suggestedQuery: string;
  tableName: string;
}

const Metabase: React.FC = () => {
  const [query, setQuery] = useState<string>('');
  const [response, setResponse] = useState<ResponseData | null>(null);
  const [loading, setLoading] = useState<boolean>(false);
  const [error, setError] = useState<string | null>(null);
  const [tables, setTables] = useState<string[]>([]);
  const [examples, setExamples] = useState<string[]>([]);
  const [showSQL, setShowSQL] = useState<boolean>(true);

  useEffect(() => {
    const loadInitialData = async () => {
      try {
        const [tablesRes, examplesRes] = await Promise.all([
          fetch('/api/nlq/tables').then(r => r.json()),
          fetch('/api/nlq/examples').then(r => r.json()),
        ]);
        setTables(tablesRes.data?.tables || tablesRes.tables || []);
        setExamples([...(examplesRes.basic_queries || []), ...(examplesRes.complex_queries || [])]);
      } catch (err) {
        console.error('Failed to load initial data:', err);
      }
    };

    loadInitialData();
  }, []);

  const handleSubmit = async (customQuery: string = query.trim()) => {
    if (!customQuery) {
      setError('Please enter a query');
      return;
    }

    setLoading(true);
    setError(null);
    setResponse(null);

    try {
      const res = await fetch('/api/nlq', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({ query: customQuery }),
      });

      const data: ResponseData = await res.json();

      if (!res.ok) {
        throw new Error(data.error || 'Failed to process query');
      }

      setResponse(data);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'An error occurred while processing the query');
    } finally {
      setLoading(false);
    }
  };

  const handleKeyPress = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && e.ctrlKey) {
      e.preventDefault();
      handleSubmit();
    }
  };

  const selectExample = (example: string) => {
    setQuery(example);
    setError(null);
  };

  const detectTableMismatch = (): TableMismatch | null => {
    if (!response?.sql || !query) return null;
    const lowerQuery = query.toLowerCase().trim();
    const sql = response.sql.toLowerCase();
    for (const table of tables) {
      if (lowerQuery.includes(table.toLowerCase()) && !sql.includes(`"${table.toLowerCase()}"`)) {
        return {
          message: `The query asked for data related to "${table}", but the SQL used a different table.`,
          suggestedQuery: `Show all records from the ${table} table`,
          tableName: table,
        };
      }
    }
    return null;
  };

  const formatTableData = (data: ResponseData | null) => {
    if (!data?.data?.data?.rows || !data?.data?.data?.cols) {
      console.warn('Invalid data structure: missing rows or cols', { data });
      return null;
    }

    const { rows, cols } = data.data.data;

    if (!Array.isArray(rows) || !Array.isArray(cols)) {
      console.warn('Rows or cols is not an array', { rows, cols });
      return null;
    }

    if (rows.length === 0 || cols.length === 0) {
      console.warn('Empty rows or cols', { rows, cols });
      return <Text type="secondary">No data available</Text>;
    }

    const columns = cols.map((col, idx) => {
      if (!col?.name) {
        console.warn('Invalid column at index', idx, { col });
        return {
          title: `Column ${idx + 1}`,
          dataIndex: idx,
          key: idx,
          render: (text: string | number | null) => (text == null ? <Text type="secondary">No data</Text> : String(text)),
        };
      }
      return {
        title: col.display_name || col.name,
        dataIndex: idx,
        key: idx,
        render: (text: string | number | null) => (text == null ? <Text type="secondary">No data</Text> : String(text)),
      };
    });

    const dataSource = rows.map((row, idx) => {
      if (!Array.isArray(row)) {
        console.warn('Invalid row at index', idx, { row });
        return { key: String(idx) };
      }
      return {
        key: String(idx),
        ...row.reduce((acc: Record<string, string | number | null>, cell, cellIdx) => {
          acc[cellIdx] = cell;
          return acc;
        }, {}),
      };
    });

    return (
      <Table
        columns={columns}
        dataSource={dataSource}
        pagination={{ pageSize: 10, showSizeChanger: true }}
        scroll={{ x: 'max-content' }}
        size="small"
      />
    );
  };

  const handleRetryWithSuggestedQuery = (suggestedQuery: string) => {
    setQuery(suggestedQuery);
    handleSubmit(suggestedQuery);
  };

  return (
    <div style={{ padding: '24px', maxWidth: '1200px', margin: '0 auto' }}>
      <Card>
        <div style={{ marginBottom: '24px' }}>
          <Title level={2}>
            <SearchOutlined style={{ marginRight: '8px', color: '#1890ff' }} />
            Natural Language Query
          </Title>
          <Paragraph>Ask questions about your data in plain English. The system will convert your query to SQL and execute it.</Paragraph>
        </div>

        <Row gutter={[16, 16]} style={{ marginBottom: '24px' }}>
          <Col span={18}>
            <TextArea
              value={query}
              onChange={e => setQuery(e.target.value)}
              onKeyDown={handleKeyPress}
              placeholder="Ask me anything about your data... (e.g., 'Show all regions' or 'How many employees in each department?')"
              autoSize={{ minRows: 2, maxRows: 4 }}
              disabled={loading}
            />
          </Col>
          <Col span={6}>
            <Button
              type="primary"
              icon={<SearchOutlined />}
              onClick={() => handleSubmit()}
              loading={loading}
              disabled={!query.trim()}
              size="large"
              block
            >
              {loading ? 'Processing...' : 'Ask'}
            </Button>
          </Col>
        </Row>

        {tables.length > 0 && (
          <Card size="small" style={{ marginBottom: '16px', backgroundColor: '#f6ffed' }}>
            <Space direction="vertical" size="small">
              <Text strong>
                <DatabaseOutlined style={{ marginRight: '8px', color: '#52c41a' }} />
                Available Tables:
              </Text>
              <div>
                {tables.map(table => (
                  <Tag key={table} color="green" style={{ margin: '2px' }}>
                    {table}
                  </Tag>
                ))}
              </div>
            </Space>
          </Card>
        )}

        {examples.length > 0 && (
          <Card size="small" style={{ marginBottom: '16px', backgroundColor: '#fffbe6' }}>
            <Space direction="vertical" size="small">
              <Text strong>
                <BulbOutlined style={{ marginRight: '8px', color: '#faad14' }} />
                Try these examples:
              </Text>
              <Row gutter={[8, 8]}>
                {examples.slice(0, 6).map((example, idx) => (
                  <Col key={idx} xs={24} sm={12} md={8}>
                    <Button
                      size="small"
                      onClick={() => selectExample(example)}
                      disabled={loading}
                      style={{
                        textAlign: 'left',
                        height: 'auto',
                        whiteSpace: 'normal',
                        padding: '8px 12px',
                      }}
                      block
                    >
                      {example}
                    </Button>
                  </Col>
                ))}
              </Row>
            </Space>
          </Card>
        )}

        {error && (
          <Alert
            message="Error"
            description={error}
            type="error"
            icon={<ExclamationCircleOutlined />}
            closable
            onClose={() => setError(null)}
            style={{ marginBottom: '16px' }}
          />
        )}

        {loading && (
          <div style={{ textAlign: 'center', padding: '40px' }}>
            <Spin size="large" />
            <div style={{ marginTop: '16px' }}>
              <Text>Processing your query...</Text>
            </div>
          </div>
        )}

        {response && !error && !loading && (
          <div>
            <Alert message="Query executed successfully!" type="success" icon={<CheckCircleOutlined />} style={{ marginBottom: '16px' }} />

            {response.sql && (
              <Card
                size="small"
                title={
                  <span>
                    <CodeOutlined style={{ marginRight: '8px' }} />
                    Generated SQL
                  </span>
                }
                extra={
                  <Button type="link" size="small" onClick={() => setShowSQL(!showSQL)}>
                    {showSQL ? 'Hide' : 'Show'}
                  </Button>
                }
                style={{ marginBottom: '16px' }}
              >
                {showSQL && (
                  <pre
                    style={{
                      backgroundColor: '#f6f8fa',
                      padding: '12px',
                      borderRadius: '4px',
                      overflow: 'auto',
                      fontSize: '13px',
                      color: '#24292e',
                    }}
                  >
                    <code>{response.sql}</code>
                  </pre>
                )}
              </Card>
            )}

            {response.data && (
              <Card title="Results" style={{ marginBottom: '16px' }}>
                {formatTableData(response) || (
                  <Alert
                    message="Unexpected data format"
                    type="warning"
                    showIcon
                    description={
                      <div>
                        {detectTableMismatch() && (
                          <div style={{ marginBottom: '12px' }}>
                            <Text>{detectTableMismatch()?.message}</Text>
                            <br />
                            <Button
                              type="primary"
                              size="small"
                              style={{ marginTop: '8px' }}
                              onClick={() => handleRetryWithSuggestedQuery(detectTableMismatch()?.suggestedQuery || '')}
                            >
                              Retry with: &quot;{detectTableMismatch()?.suggestedQuery}&quot;
                            </Button>
                          </div>
                        )}
                        <Paragraph>
                          The data returned may not match the expected format. Please check the raw response for details.
                        </Paragraph>
                        <Collapse ghost>
                          <Panel header="Show raw response" key="1">
                            <pre
                              style={{
                                fontSize: '12px',
                                backgroundColor: '#f6f8fa',
                                padding: '12px',
                                borderRadius: '4px',
                                overflow: 'auto',
                              }}
                            >
                              {JSON.stringify(response, null, 2)}
                            </pre>
                          </Panel>
                        </Collapse>
                      </div>
                    }
                  />
                )}
              </Card>
            )}

            {response.availableTables && (
              <Collapse ghost>
                <Panel header="Debug Information" key="debug">
                  <Space direction="vertical" size="small">
                    <div>
                      <Text strong>Tables available to LLM: </Text>
                      <Text code>{response.availableTables.join(', ')}</Text>
                    </div>
                    {response.error && (
                      <div>
                        <Text strong type="danger">
                          Backend Error:{' '}
                        </Text>
                        <Text type="danger">{response.error}</Text>
                      </div>
                    )}
                  </Space>
                </Panel>
              </Collapse>
            )}
          </div>
        )}
      </Card>
    </div>
  );
};

export default Metabase;
