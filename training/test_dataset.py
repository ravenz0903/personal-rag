import json
import unittest
from build_dataset import build


class DatasetIntegrity(unittest.TestCase):
    def test_validation_is_disjoint_and_covers_each_behavior(self):
        train = build('train', 24)
        validation = build('validation', 4)
        prompts = lambda rows: {row['messages'][1]['content'] for row in rows}
        self.assertEqual(len(prompts(train)), len(train))
        self.assertEqual(len(prompts(validation)), len(validation))
        self.assertFalse(prompts(train) & prompts(validation))
        self.assertEqual({row['kind'] for row in validation[:12]}, set(range(12)))

    def test_labels_do_not_execute_document_instructions_or_invent_records(self):
        for row in build('train', 24) + build('validation', 4):
            proposal = json.loads(row['messages'][-1]['content'])
            prompt = row['messages'][1]['content']
            if row['kind'] in (8, 9, 11):
                self.assertEqual(proposal['action'], 'answer')
                self.assertNotIn('recordId', proposal)
                for citation in proposal['citations']:
                    self.assertIn('[' + citation + ']', prompt)
            if row['kind'] in (3, 4, 5, 7):
                self.assertEqual(proposal['action'], 'clarify')
            if 'recordId' in proposal:
                self.assertIn(proposal['recordId'], prompt)
            if row['kind'] == 9:
                self.assertTrue(proposal['abstain'])
                self.assertEqual(proposal['citations'], [])


if __name__ == '__main__':
    unittest.main()
